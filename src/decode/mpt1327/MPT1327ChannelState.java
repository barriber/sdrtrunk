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
package decode.mpt1327;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import message.Message;
import sample.Listener;
import source.config.SourceConfigTuner;
import alias.AliasList;
import audio.SquelchListener;
import audio.SquelchListener.SquelchState;
import controller.activity.CallEvent;
import controller.activity.CallEvent.CallEventType;
import controller.channel.Channel;
import controller.channel.Channel.ChannelType;
import controller.channel.ChannelMap;
import controller.channel.ProcessingChain;
import controller.state.AuxChannelState;
import controller.state.ChannelState;
import decode.config.DecodeConfigMPT1327;
import decode.mpt1327.MPT1327Message.IdentType;

public class MPT1327ChannelState extends ChannelState
{
	private final static Logger mLog = LoggerFactory.getLogger( MPT1327ChannelState.class );

	private String mSite;
	private String mFromTalkgroup;
	private String mToTalkgroup;
	private int mChannelNumber;
	private ChannelType mChannelType;
	private ChannelMap mChannelMap;
	private MPT1327ActivitySummary mActivitySummary;
	
	public MPT1327ChannelState( ProcessingChain processingChain, 
								AliasList aliasList,
								ChannelMap channelMap )
	{
		super( processingChain, aliasList );

		mActivitySummary = new MPT1327ActivitySummary( aliasList );
		
		mChannelMap = channelMap;

		mChannelType = processingChain.getChannel().getChannelType();
	}
	
	public void dispose()
	{
		super.dispose();
		
		mActivitySummary.dispose();
		mActivitySummary = null;
		mCurrentCallEvent = null;
	}
	
	public void setCurrentCall( MPT1327CallEvent event )
	{
		mCurrentCallEvent = event;
	}
	
	@Override
    public String getActivitySummary()
    {
		StringBuilder sb = new StringBuilder();
		
		sb.append( mActivitySummary.getSummary() );
		
		for( AuxChannelState state: mAuxChannelStates )
		{
			sb.append( state.getActivitySummary() );
			sb.append( "\n\n" );
		}
		
		return sb.toString();
    }
	
	@Override
    public void receive( Message message )
    {
		super.receive( message );
		
		if( message instanceof MPT1327Message )
		{
			mActivitySummary.receive( message );
			
			MPT1327Message mpt = (MPT1327Message)message;
			
			if( mpt.isValid() )
			{
				switch( mpt.getMessageType() )
				{
					case ACK:
						IdentType identType = ( (MPT1327Message) message ).getIdent1Type();

						if( identType == IdentType.REGI )
						{
							mCallEventModel.add( 
									new MPT1327CallEvent.Builder( CallEventType.REGISTER )
										.aliasList( getProcessingChain().getAliasList() )
										.channel( String.valueOf( mChannelNumber ) )
										.details( "REGISTERED ON NETWORK" )
										.frequency( mChannelMap.getFrequency( mChannelNumber ) )
										.from( mpt.getToID() )
										.to( mpt.getFromID() )
										.build() );
						}
						else
						{
							mCallEventModel.add( 
									new MPT1327CallEvent.Builder( CallEventType.ACKNOWLEDGE )
										.aliasList( getProcessingChain().getAliasList() )
										.channel( String.valueOf( mChannelNumber ) )
										.details( "ACK " + identType.getLabel() )
										.frequency( mChannelMap.getFrequency( mChannelNumber ) )
										.from( mpt.getFromID() )
										.to( mpt.getToID() )
										.build() );
						}
						break;
					case AHYC:
						mCallEventModel.add( 
								new MPT1327CallEvent.Builder( CallEventType.REQUEST )
									.aliasList( getProcessingChain().getAliasList() )
									.channel( String.valueOf( mChannelNumber ) )
									.details( ( (MPT1327Message) message ).getRequestString() )
									.frequency( mChannelMap.getFrequency( mChannelNumber ) )
									.from( mpt.getFromID() )
									.to( mpt.getToID() )
									.build() );
						break;
					case AHYQ:
						mCallEventModel.add( 
								new MPT1327CallEvent.Builder( CallEventType.STATUS )
									.aliasList( getProcessingChain().getAliasList() )
									.channel( String.valueOf( mChannelNumber ) )
									.details( mpt.getStatusMessage() )
									.frequency( mChannelMap.getFrequency( mChannelNumber ) )
									.from( mpt.getFromID() )
									.to( mpt.getToID() )
									.build() );
						break;
					case ALH:
						String site = mpt.getSiteID();
						
						if( mSite == null )
						{
							mSite = site;
							broadcastChange( ChangedAttribute.CHANNEL_SITE_NUMBER  );
						}
						else if( site != null && !site.contentEquals( mSite ) )
						{
							mSite = site;
							broadcastChange( ChangedAttribute.CHANNEL_SITE_NUMBER  );
						}
						
						setState( State.CONTROL );
						break;
					case GTC:
						if( mpt.isValidCall() )
						{
							int channelNumber = mpt.getChannel();
							
							if( !getProcessingChain().getChannel()
										.hasTrafficChannel( channelNumber ) )
							{
								String aliasListName = mProcessingChain
											.getChannel().getAliasListName();
								
								Channel traffic = getTrafficChannel( channelNumber, 
																aliasListName );
								
								/* Set the system and site to same as control channel */
								traffic.setSystem( getProcessingChain().getChannel()
										.getSystem(), false );
								traffic.setSite( getProcessingChain().getChannel()
										.getSite(), false );

								/* Add the traffic channel to the parent control channel */
								getProcessingChain().getChannel()
											.addTrafficChannel( channelNumber, traffic );

								/* Start the traffic channel */
								traffic.setEnabled( true );
								
								/* Set traffic channel state info */
								MPT1327ChannelState trafficState = 
										(MPT1327ChannelState)traffic
											.getProcessingChain().getChannelState();
								
								MPT1327ChannelState controlState = 
										(MPT1327ChannelState)getProcessingChain()
													.getChannelState();
								
								trafficState.setChannelMap( controlState.getChannelMap() );
								trafficState.setChannelNumber( channelNumber );
								trafficState.setFromTalkgroup( mpt.getFromID() );
								trafficState.setToTalkgroup( mpt.getToID() );

								/* Add this control channel as message listener on the
								 * traffic channel so we can receive call tear-down
								 * messages */
								traffic.addListener( (Listener<Message>)this );
								
								CallEventType type = traffic.isProcessing() ? 
											CallEventType.CALL : 
											CallEventType.CALL_NO_TUNER;
								
								/*
								 * Set the traffic channel's call event model to
								 * share the control channel's call event model
								 */
								traffic.getProcessingChain().getChannelState()
										.setCallEventModel( mCallEventModel );

								MPT1327CallEvent callStartEvent = 
										new MPT1327CallEvent.Builder( type )
								.aliasList( traffic.getProcessingChain().getAliasList() )
								.channel( String.valueOf( channelNumber ) )
								.frequency( mChannelMap.getFrequency( channelNumber ) )
								.from( mpt.getFromID() )
								.to( mpt.getToID() )
								.build();
								
								traffic.getProcessingChain().getChannelState()
									.setCurrentCallEvent( callStartEvent );

								mCallEventModel.add( callStartEvent );
							}
						}
						break;
					case CLEAR:
					case MAINT:
						if( mChannelType == ChannelType.TRAFFIC )
						{
							fade( CallEventType.CALL_END );
						}
						break;
					case HEAD_PLUS1:
					case HEAD_PLUS2:
					case HEAD_PLUS3:
					case HEAD_PLUS4:
						mCallEventModel.add( 
								new MPT1327CallEvent.Builder( CallEventType.SDM )
									.aliasList( mAliasList )
									.details( mpt.getMessage() )
									.from( mpt.getFromID() )
									.to( mpt.getToID() )
									.build() );
						break;
				}
			}
		}
    }
	
	/**
	 * Intercept the fade event so that we can generate a call end event
	 */
	@Override
	public void fade( final CallEventType type )
	{
		/*
		 * We can receive multiple call tear-down messages -- only post a call
		 * end event for the one that can change the state to fade
		 */
		if( getState().canChangeTo( State.FADE ) )
		{
			CallEvent current = getCurrentCallEvent();

			if( current != null )
			{
				mCallEventModel.setEnd( current );
			}
			else
			{
				mCallEventModel.add( 
						new MPT1327CallEvent.Builder( type )
							.aliasList( getAliasList() )
							.channel( String.valueOf( mChannelNumber ) )
							.frequency( mChannelMap.getFrequency( mChannelNumber ) )
							.from( mFromTalkgroup )
							.to( mToTalkgroup )
							.build() );
			}
			
			setCurrentCall( null );
		}
		
		super.fade( type );
	}
	
    /**
     * Make the ConventionalChannelState always unsquelched
     */
    public void addListener( SquelchListener listener )
    {
        super.addListener( listener );
        
        super.setSquelchState( SquelchState.UNSQUELCH );
    }
    
	public void reset()
	{
		mFromTalkgroup = null;
		broadcastChange( ChangedAttribute.FROM_TALKGROUP );
		mToTalkgroup = null;
		broadcastChange( ChangedAttribute.TO_TALKGROUP );
		
		super.reset();
	}
	
	public String getSite()
	{
		return mSite;
	}
	
	public ChannelMap getChannelMap()
	{
		return mChannelMap;
	}
	
	public void setChannelMap( ChannelMap channelMap )
	{
		mChannelMap = channelMap;
	}
	
	public String getFromTalkgroup()
	{
		return mFromTalkgroup;
	}

	/**
	 * Set the talkgroup.  This is used primarily for traffic channels since
	 * the talkgroup will already have been identified prior to the traffic
	 * channel being created.
	 */
	public void setFromTalkgroup( String fromTalkgroup )
	{
		mFromTalkgroup = fromTalkgroup;
		
		broadcastChange( ChangedAttribute.FROM_TALKGROUP );
	}
	
	public String getToTalkgroup()
	{
		return mToTalkgroup;
	}

	/**
	 * Set the talkgroup.  This is used primarily for traffic channels since
	 * the talkgroup will already have been identified prior to the traffic
	 * channel being created.
	 */
	public void setToTalkgroup( String toTalkgroup )
	{
		mToTalkgroup = toTalkgroup;
		
		broadcastChange( ChangedAttribute.TO_TALKGROUP );
	}
	
	public int getChannelNumber()
	{
		return mChannelNumber;
	}
	/**
	 * Set the channel number.  This is used primarily for traffic channels since
	 * the channel will already have been identified prior to the traffic
	 * channel being created.
	 */
	public void setChannelNumber( int channel )
	{
		mChannelNumber = channel;
		
		broadcastChange( ChangedAttribute.CHANNEL_NUMBER );
	}
	
	public String getAliasListName()
	{
		return mProcessingChain.getChannel().getAliasListName();
	}
	
	private Channel getTrafficChannel( int channelNumber, String aliasListName )
	{
		Channel traffic = new Channel( "Traffic Channel " + channelNumber, 
									   ChannelType.TRAFFIC );

		traffic.setResourceManager( getProcessingChain().getResourceManager() );
		
		SourceConfigTuner source = new SourceConfigTuner();

		long frequency = mChannelMap.getFrequency( channelNumber );
		
		source.setFrequency( frequency );
		
		traffic.setSourceConfiguration( source );

		DecodeConfigMPT1327 decode = new DecodeConfigMPT1327();
		
		traffic.setDecodeConfiguration( decode );
		
		traffic.setAliasListName( aliasListName );
		
		return traffic;
	}
}
