/*******************************************************************************
 *     SDR Trunk 
 *     Copyright (C) 2014 Dennis Sheirer
 * 
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 * 
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 * 
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>
 ******************************************************************************/
package controller.channel;

import gui.SDRTrunk;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import message.Message;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import record.Recorder;
import record.wave.ComplexWaveRecorder;
import record.wave.FloatWaveRecorder;
import sample.Broadcaster;
import sample.Listener;
import sample.complex.ComplexSample;
import sample.real.RealBuffer;
import sample.real.RealSampleListener;
import source.ComplexSource;
import source.RealSource;
import source.Source;
import source.SourceException;
import source.config.SourceConfigMixer;
import source.tuner.Tuner;
import source.tuner.TunerChannelSource;
import source.tuner.frequency.AutomaticFrequencyControl;
import source.tuner.frequency.FrequencyCorrectionControl;
import alias.AliasList;
import audio.IAudioOutput;
import audio.IAudioTypeListener;
import audio.SquelchListener;
import controller.ResourceManager;
import controller.ThreadPoolManager.ThreadType;
import controller.activity.CallEvent;
import controller.channel.Channel.ChannelType;
import controller.state.ChannelState;
import controller.state.ChannelState.State;
import decode.Decoder;
import decode.DecoderFactory;
import eventlog.EventLogType;
import eventlog.EventLogger;

/**
 * A channel has all of the pieces needed to wire together a source, decoder,
 * event logger and recorder and be started and stopped.
 */
public class ProcessingChain implements Listener<Message>
{
	private final static Logger mLog = LoggerFactory.getLogger( SDRTrunk.class );

	protected AtomicBoolean mRunning = new AtomicBoolean();
    protected Channel mChannel;
	protected Source mSource;
	protected Decoder mDecoder;
	protected List<Recorder> mRecorders = new ArrayList<Recorder>();
	protected ArrayList<EventLogger> mEventLoggers = new ArrayList<EventLogger>();
	protected AutomaticFrequencyControl mAFC;
	protected ChannelState mChannelState;
	protected ResourceManager mResourceManager;
	private ScheduledFuture<?> mProcessorTask;
	private AliasList mAliasList;

	private ComplexSampleReceiver mComplexReceiver = new ComplexSampleReceiver();
	private LinkedTransferQueue<List<ComplexSample>> mComplexQueue = 
								new LinkedTransferQueue<List<ComplexSample>>();

	private RealSampleReceiver mRealReceiver = new RealSampleReceiver();
	private LinkedTransferQueue<RealBuffer> mRealQueue = 
									new LinkedTransferQueue<RealBuffer>();

	protected Broadcaster<Message> mBroadcaster = new Broadcaster<Message>();

	public ProcessingChain( Channel channel, ResourceManager resourceManager )
	{
		mChannel = channel;
		mResourceManager = resourceManager;
		
		if( mResourceManager != null )
		{
			mAliasList = mResourceManager.getPlaylistManager()
					.getPlayist().getAliasDirectory().getAliasList(  
							mChannel.getAliasListName() );
		}
	}
	
	public IAudioOutput getAudioOutput()
	{
		if( mDecoder != null )
		{
			return mDecoder.getAudioOutput();
		}
		
		return null;
	}

	public Listener<List<ComplexSample>> getComplexReceiver()
	{
		return mComplexReceiver;
	}
	
	public Listener<RealBuffer> getRealReceiver()
	{
		return (Listener<RealBuffer>)mRealReceiver;
	}
	
	public String toString()
	{
		return mChannel.getName();
	}
	
	public AliasList getAliasList()
	{
		return mAliasList;
	}
	
	public void dispose()
	{
		if( mProcessorTask != null )
		{
			mResourceManager.getThreadPoolManager().cancel( mProcessorTask );
		}
		
		if( mChannelState != null )
		{
			mChannelState.dispose();
		}
		
		mChannelState = null;

		if( mDecoder != null )
		{
			mDecoder.dispose();
		}

		mBroadcaster.dispose();
		mEventLoggers.clear();
		mRecorders.clear();
		
		mResourceManager = null;
	}
	
	public ResourceManager getResourceManager()
	{
		return mResourceManager;
	}
	
    public void start()
    {
		if( !mRunning.get() && mChannel.getEnabled() )
		{
			updateSource();

			updateDecoder();
			
			updateEventLogging();
			
			updateRecording();

			mRunning.set( true );
		}
		else
		{
			mLog.info( getLogPrefix() + "start() invoked, but processing chain is "
					+ "already started" );
		}
    }

	/**
	 * Performs closeout operations to prepare for channel deletion
	 */
	public void stop()
	{
		if( mRunning.get() )
		{
			updateSource();
			
			updateDecoder();
				
			updateRecording();

			/* Flush call events to the logger */
			if( getChannel().getChannelType() == ChannelType.STANDARD )
			{
				getChannelState().getCallEventModel().flush();
			}

			updateEventLogging();
			
			mRunning.set( false );
		}
		else
		{
			mLog.info( getLogPrefix() + "stop() invoked, but processing chain was "
					+ "already stopped" );
		}
	}

	private String getLogPrefix()
	{
		return "Channel [" + mChannel.getName() + "] - ";
	}
	
	public void updateSource()
	{
		/* Cleanup existing source */
		if( mSource != null )
		{
			if( mSource instanceof ComplexSource )
			{
				((ComplexSource)mSource).removeListener( this.getComplexReceiver() );
			}
			else if( mSource instanceof RealSource )
			{
				((RealSource)mSource)
					.removeListener( this.getRealReceiver() );
			}
			
			if( mProcessorTask != null )
			{
				mResourceManager.getThreadPoolManager().cancel( mProcessorTask );
			}
			
			mSource.dispose();
			
			mSource = null;
		}
		
		/* Get a new source if needed and start the sample processor thread */
		if( mChannel.getEnabled() && mResourceManager != null )
		{
			try
            {
	            mSource = mResourceManager.getSourceManager()
	            					.getSource( ProcessingChain.this );
            }
            catch ( SourceException e )
            {
            	mLog.error( "Couldn't obtain source for processing chain [" + 
            					toString() + "]", e );
            	
            	mSource = null;
            }

			if( mSource != null )
			{
				if( mSource instanceof ComplexSource )
				{
					try
					{
						mProcessorTask = 
								mResourceManager.getThreadPoolManager()
									.scheduleFixedRate( ThreadType.DECODER, 
											   new ComplexProcessor(), 
											   50, TimeUnit.MILLISECONDS );

						((ComplexSource)mSource)
							.setListener( this.getComplexReceiver() );
					}
					catch( RejectedExecutionException ree )
					{
						mLog.error( getLogPrefix() + "ProcessingChain - "
								+ "error scheduling complex sample processing "
								+ "thread - " + ree.getLocalizedMessage() );
						
						mSource.dispose();
					}
				}
				else if( mSource instanceof RealSource )
				{
					try
					{
						mProcessorTask = 
								mResourceManager.getThreadPoolManager()
									.scheduleFixedRate( ThreadType.DECODER, 
											   new RealProcessor(), 
											   50, TimeUnit.MILLISECONDS );

						((RealSource)mSource)
								.setListener( this.getRealReceiver() );
					}
					catch( RejectedExecutionException ree )
					{
						mLog.error( getLogPrefix() + "ProcessingChain - "
								+ "error scheduling float sample processing "
								+ "thread", ree );
						
						mSource.dispose();
					}
				}
			}
		}
	}
	
	public void updateDecoder()
	{
		if( mChannel.getEnabled() )
		{
			AliasList aliasList = null;
			
			if( mResourceManager != null )
			{
				aliasList = mResourceManager.getPlaylistManager()
						.getPlayist().getAliasDirectory().getAliasList(  
								mChannel.getAliasListName() );
			}

			/* Setup the channel state */
			mChannelState = DecoderFactory.getChannelStateNew( this, aliasList );

			if( mSource == null )
			{
				mChannelState.setState( State.NO_TUNER );
			}
			else
			{
				mDecoder = DecoderFactory.
						getDecoder( this, mSource.getSampleType(), aliasList );
				
				if( mDecoder != null )
				{
					/* Register to receive decoded messages and auxiliary messages */
					mDecoder.addMessageListener( ProcessingChain.this );

					/* Establish two-way communication between the frequency 
					 * correction controller and the tuner channel source */
					if( mSource instanceof TunerChannelSource &&
						mDecoder.hasFrequencyCorrectionControl() )
					{
						TunerChannelSource tcs = (TunerChannelSource)mSource;
						
						FrequencyCorrectionControl fcc = 
								mDecoder.getFrequencyCorrectionControl();
						
						if( fcc != null )
						{
							tcs.addListener( fcc );
							fcc.setListener( tcs );
						}
					}
					
					/* Register audio output to be controlled by the channel state */
					mChannelState.addListener( (SquelchListener)mDecoder.getAudioOutput() );
					mChannelState.setListener( (IAudioTypeListener)mDecoder.getAudioOutput() );
				}
				
			}
		}
	}
	
	/**
	 * Starts, stops or changes event logging
	 */
	public void updateEventLogging()
	{
		/* Shutdown and remove existing event loggers */
		if( mEventLoggers != null )
		{
			for( EventLogger logger: mEventLoggers )
			{
				logger.stop();
			}
			
			mEventLoggers.clear();
		}
		
		/* Construct event loggers and add as message listeners */
		if( mChannel.getEnabled() && 
			mResourceManager != null )
		{
			//Get the event loggers
			ArrayList<EventLogType> types = getChannel()
					.getEventLogConfiguration().getLoggers();

			for( EventLogType type: types )
			{
				EventLogger logger;
				
				if( type == EventLogType.CALL_EVENT )
				{
					logger = mResourceManager.getEventLogManager()
							.getLogger( this, type );
					
					getChannelState().getCallEventModel()
						.addListener( (Listener<CallEvent>)logger );
				}
				else
				{
					logger = mResourceManager.getEventLogManager()
							.getLogger( this, type );

					addListener( (Listener<Message>)logger );
				}
				
				logger.start();

				mEventLoggers.add( logger );
			}
		}
	}

	/**
	 * Starts, stops or changes data recording
	 */
	public void updateRecording()
	{
		/* Remove any existing recorders */
		if( mRecorders != null )
		{
			for( Recorder recorder: mRecorders )
			{
				if( recorder != null )
				{
					try
	                {
		                recorder.stop();
	                }
					catch ( IOException ioe )
					{
						mLog.error( getLogPrefix() + "Couldn't stop recorder [" + 
		                		recorder.getFileName() + "]", ioe );
					}
				}
			}
		}

		/* Start any new recorders */
		if( mChannel.getEnabled() && 
			mResourceManager != null && 
			mDecoder != null )
		{
			mRecorders = mResourceManager.getRecorderManager()
					.getRecorder( this );

			/* Iterate any returned recorders and start/add them */
			for( Recorder recorder: mRecorders )
			{
				try
	            {
					recorder.start();

					if( recorder instanceof FloatWaveRecorder )
					{
						mLog.info( getLogPrefix() + "- started audio recording [" + 
								recorder.getFileName() + "]" );

						mDecoder.addRealSampleListener( (FloatWaveRecorder)recorder );
					}
					else if( recorder instanceof ComplexWaveRecorder )
					{
						mLog.info( getLogPrefix() + "- started baseband recording [" + 
								recorder.getFileName() + "]" );

						mDecoder.addComplexListener( (ComplexWaveRecorder )recorder );
					}
					
	            }
	            catch ( IOException e )
	            {
	            	mLog.error( getLogPrefix() + "Unable to start recorder [" + 
	            							recorder.getFileName() + "]" );
	            }
			}
		}
	}
	
	public Channel getChannel()
	{
		return mChannel;
	}
	
	public Source getSource()
	{
		return mSource;
	}
	
	/**
	 * Returns the tuner that is sourcing this channel, or null if it's being
	 * sourced from some other source
	 */
	public Tuner getTuner()
	{
		Tuner retVal = null;
		
		if( mSource instanceof TunerChannelSource )
		{
			retVal = ((TunerChannelSource)mSource).getTuner();
		}
		
		return retVal;
	}
	
	public Decoder getDecoder()
	{
		return mDecoder;
	}

	public ChannelState getChannelState()
	{
		return mChannelState;
	}
	
	public boolean isRunning()
	{
		return mRunning.get();
	}
	
	public boolean isProcessing()
	{
		return isRunning() && mSource != null;
	}

	private source.mixer.MixerChannel getMixerChannel()
	{
		source.mixer.MixerChannel retVal = null;
		
		if( mChannel.getSourceConfiguration() instanceof SourceConfigMixer )
		{
			SourceConfigMixer mixer = (SourceConfigMixer)mChannel.getSourceConfiguration();
			
			retVal = mixer.getChannel();
		}

		return retVal;
	}

	@Override
    public void receive( Message message )
    {
		if( message.isValid() )
		{
			mBroadcaster.receive( message );
		}
		
    }

	/**
	 * Adds the listener to receive demodulated samples from the decoder
	 */
    public void addRealListener( RealSampleListener listener )
    {
		if( mDecoder != null )
		{
			mDecoder.addRealSampleListener( listener );
		}
    }

    /**
     * Removes the listener from receiving demodulated samples from the decoder
     */
    public void removeRealListener( RealSampleListener listener )
    {
		if( mDecoder != null )
		{
			mDecoder.removeRealListener( listener );
		}
    }

    /**
     * Adds a listener to receive a copy of complex (I/Q) baseband samples 
     * received by this processing chain
     */
    public void addComplexListener( Listener<ComplexSample> listener )
    {
		if( mDecoder != null )
		{
			mDecoder.addComplexListener( listener );
		}
		else
		{
			mLog.error( getLogPrefix() + "attempt to add a complex sample "
					+ "listener, but the decoder is null" );
		}
    }

    /**
     * Removes the listener from receiving a copy of complex (I/Q) baseband 
     * samples received by this processing chain
     */
    public void removeComplexListener( Listener<ComplexSample> listener )
    {
		if( mDecoder != null )
		{
			mDecoder.removeComplexListener( listener );
		}
		else
		{
			mLog.error( getLogPrefix() + "attempt to remove a complex sample "
					+ "listener, but the decoder is null" );
		}
    }

    /**
     * Registers the listener to receive messages
     */
    public void addListener( Listener<Message> listener )
    {
    	mBroadcaster.addListener( listener );
    }
    
    /**
     * Registers all listeners in the list to receive messages
     */
    public void addListeners( List<Listener<Message>> listeners )
    {
    	for( Listener<Message> listener: listeners )
    	{
    		addListener( listener );
    	}
    }

    /**
     * Removes the listener from receiving messages
     */
    public void removeListener( Listener<Message> listener )
    {
		mBroadcaster.removeListener( listener );
    }

    /**
     * Manages the real sample queue and distributes samples to the decoder.
     */
	private class RealProcessor implements Runnable
	{
		@Override
        public void run()
        {
			try
			{
				List<RealBuffer> sampleBuffers = new ArrayList<RealBuffer>();

				mRealQueue.drainTo( sampleBuffers, 4 );

				for( RealBuffer sampleBuffer: sampleBuffers )
				{
					if( mDecoder != null )
					{
						float[] samples = sampleBuffer.getSamples();
						
						for( float sample: samples )
						{
							mDecoder.getRealReceiver().receive( sample );
						}
					}
					
					sampleBuffer.dispose();
				}
				
				sampleBuffers.clear();
			}
			catch( Exception e )
			{
				mLog.error( "error during processing chain real processor run", e );
			}
        }
	}

	/**
     * Manages the complex sample queue and distributes samples to the decoder.
     */
	private class ComplexProcessor implements Runnable
	{
		@Override
        public void run()
        {
			try
			{
				List<List<ComplexSample>> sampleSets = 
						new ArrayList<List<ComplexSample>>();

				mComplexQueue.drainTo( sampleSets, 16 );
			
				for( List<ComplexSample> samples: sampleSets )
				{
					for( ComplexSample sample: samples )
					{
						if( sample != null && mDecoder != null )
						{
							mDecoder.getComplexReceiver().receive( sample );
						}
					}
					
					samples.clear();
				}
				
				sampleSets.clear();
			}
			catch( Exception e )
			{
				mLog.error( "error during processing chain complex processor run", e );
			}
        }
	}
	
	/**
	 * Internal listener to receive complex (I/Q) baseband samples.  Places 
	 * received samples into the queue managed by the ComplexProcessor.
	 */
	public class ComplexSampleReceiver implements Listener<List<ComplexSample>>
	{
		@Override
        public void receive( List<ComplexSample> samples )
        {
			mComplexQueue.add( samples );
        }
	}

	/**
	 * Internal listener to receive real(ie demodulated) samples.  Places
	 * received samples into the queue managed by the RealProcessor
	 */
	public class RealSampleReceiver implements Listener<RealBuffer>
	{
		@Override
        public void receive( RealBuffer samples )
        {
			mRealQueue.add( samples );
        }
	}
}
