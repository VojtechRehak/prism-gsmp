//==============================================================================
//	
//	Copyright (c) 2002-
//	Authors:
//	* Dave Parker <david.parker@comlab.ox.ac.uk> (University of Oxford)
//	
//------------------------------------------------------------------------------
//	
//	This file is part of PRISM.
//	
//	PRISM is free software; you can redistribute it and/or modify
//	it under the terms of the GNU General Public License as published by
//	the Free Software Foundation; either version 2 of the License, or
//	(at your option) any later version.
//	
//	PRISM is distributed in the hope that it will be useful,
//	but WITHOUT ANY WARRANTY; without even the implied warranty of
//	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//	GNU General Public License for more details.
//	
//	You should have received a copy of the GNU General Public License
//	along with PRISM; if not, write to the Free Software Foundation,
//	Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
//	
//==============================================================================

package explicit;

import java.util.List;

/**
 * Interface for classes that represent an explicit-state GSMP.
 * 
 * GSMP is a model driven by events with general time distributions.
 * GSMP may have any number of events, and any number of events can be active at any given time.
 * Out of the active states, only one "wins" by occuring the soonest.
 * Each event has a distribution on states for each state, determining the next state.
 */
public interface GSMP extends ModelSimple // TODO MAJO - incomplete
{
	/**
	 * Get all events.
	 */
	public List<GSMPEvent> getEventList();


	/**
	 * Returns a list of events active in state {@code state}.
	 */
	public List<GSMPEvent> getActiveEvents(int state);
	
	/**
	 * Adds an event into the GSMP.
	 * @param event to add
	 */
	public void addEvent(GSMPEvent event);

}