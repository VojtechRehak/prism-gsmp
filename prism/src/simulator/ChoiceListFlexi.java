//==============================================================================
//	
//	Copyright (c) 2002-
//	Authors:
//	* Dave Parker <david.parker@comlab.ox.ac.uk> (University of Oxford)
//	* Mario Uhrik <433501@mail.muni.cz> (Masaryk University)
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

package simulator;

import java.util.*;

import parser.*;
import parser.ast.*;
import parser.ast.Module;
import parser.type.TypeDistributionExponential;
import prism.ModelType;
import prism.PrismException;
import prism.PrismLangException;

public class ChoiceListFlexi implements Choice
{
	// Module/action info, encoded as an integer.
	// For an independent (non-synchronous) choice, this is -i,
	// where i is the 1-indexed module index.
	// For a synchronous choice, this is the 1-indexed action index.
	protected int moduleOrActionIndex;
	
	//GSMP event identifiers
	protected List<String> eventIdents;
	
	// ExpSyncBackwardCompatible flag from prism settings
	public boolean expSyncBackwardCompatible;

	// List of multiple updates and associated probabilities/rates
	// Size of list is stored implicitly in target.length
	// Probabilities/rates are already evaluated, target states are not
	// but are just stored as lists of updates (for efficiency)
	protected List<List<Update>> updates;
	protected List<Double> probability;

	/**
	 * Create empty choice.
	 */
	public ChoiceListFlexi()
	{
		updates = new ArrayList<List<Update>>();
		probability = new ArrayList<Double>();
		eventIdents = new ArrayList<String>();
		expSyncBackwardCompatible = true;
	}

	/**
	 * Copy constructor.
	 * NB: Does a shallow, not deep, copy with respect to references to Update objects.
	 */
	public ChoiceListFlexi(ChoiceListFlexi ch)
	{
		moduleOrActionIndex = ch.moduleOrActionIndex;
		updates = new ArrayList<List<Update>>(ch.updates.size());
		eventIdents = ch.getEventIdents();
		expSyncBackwardCompatible = ch.expSyncBackwardCompatible;
		for (List<Update> list : ch.updates) {
			List<Update> listNew = new ArrayList<Update>(list.size()); 
			updates.add(listNew);
			for (Update up : list) {
				listNew.add(up);
			}
		}
		probability = new ArrayList<Double>(ch.size());
		for (double p : ch.probability) {
			probability.add(p);
		}
	}

	// Set methods

	/**
	 * Set the module/action for this choice, encoded as an integer
	 * (-i for independent in ith module, i for synchronous on ith action)
	 * (in both cases, modules/actions are 1-indexed)
	 */
	public void setModuleOrActionIndex(int moduleOrActionIndex)
	{
		this.moduleOrActionIndex = moduleOrActionIndex;
	}

	/**
	 * Add a transition to this choice.
	 * @param probability Probability (or rate) of the transition
	 * @param ups List of Update objects defining transition
	 */
	public void add(double probability, List<Update> ups)
	{
		// assumes all the added updates are from the same command (by default, they are)
		if (ups.get(0).getParent().getParent().getEventIdent() != null) {
			this.eventIdents.add(ups.get(0).getParent().getParent().getEventIdent().getName());
		} else {
			this.eventIdents.add(null);
		}
		this.updates.add(ups);
		this.probability.add(probability);
	}

	@Override
	public void scaleProbabilitiesBy(double d)
	{
		int i, n;
		n = size();
		for (i = 0; i < n; i++) {
			probability.set(i, probability.get(i) * d);
		}
	}
	
	public void setEventIdent(int i, String eventIdent) {
		eventIdents.set(i, eventIdent);
	}

	/**
	 * Modify this choice, constructing product of it with another.
	 */
	public void productWith(ChoiceListFlexi ch) throws PrismException
	{
		List<Update> list;
		int i, j, n, n2;
		double pi;

		n = ch.size();
		n2 = size();
		
		eventProduct(ch);
		
		// Loop through each (ith) element of new choice (skipping first)
		for (i = 1; i < n; i++) {
			pi = ch.getProbability(i);
			// Loop through each (jth) element of existing choice
			for (j = 0; j < n2; j++) {
				// Create new element (i,j) of product 
				list = new ArrayList<Update>(updates.get(j).size() + ch.updates.get(i).size());
				for (Update u : updates.get(j)) {
					list.add(u);
				}
				for (Update u : ch.updates.get(i)) {
					list.add(u);
				}
				add(pi * getProbability(j), list);
			}
		}
		// Modify elements of current choice to get (0,j) elements of product
		pi = ch.getProbability(0);
		for (j = 0; j < n2; j++) {
			for (Update u : ch.updates.get(0)) {
				updates.get(j).add(u);
			}
			probability.set(j, pi * probability.get(j));
		}
	}

	// Get methods
	
	public String getEventIdent(int i) {
		return eventIdents.get(i);
	}
	
	public List<String> getEventIdents() {
		return eventIdents;
	}

	@Override
	public int getModuleOrActionIndex()
	{
		return moduleOrActionIndex;
	}

	@Override
	public String getModuleOrAction()
	{
		// Action label (or absence of) will be the same for all updates in a choice
		Update u = updates.get(0).get(0);
		Command c = u.getParent().getParent();
		if ("".equals(c.getSynch()))
			return c.getParent().getName();
		else
			return "[" + c.getSynch() + "]";
	}

	@Override
	public int size()
	{
		return probability.size();
	}

	@Override
	public String getUpdateString(int i, State currentState) throws PrismLangException
	{
		int j, n;
		String s = "";
		boolean first = true;
		for (Update up : updates.get(i)) {
			n = up.getNumElements();
			for (j = 0; j < n; j++) {
				if (first)
					first = false;
				else
					s += ", ";
				s += up.getVar(j) + "'=" + up.getExpression(j).evaluate(currentState);
			}
		}
		return s;
	}

	@Override
	public String getUpdateStringFull(int i)
	{
		String s = "";
		boolean first = true;
		for (Update up : updates.get(i)) {
			if (up.getNumElements() == 0)
				continue;
			if (first)
				first = false;
			else
				s += " & ";
			s += up;
		}
		return s;
	}

	@Override
	public State computeTarget(int i, State currentState) throws PrismLangException
	{
		State newState = new State(currentState);
		for (Update up : updates.get(i))
			up.update(currentState, newState);
		return newState;
	}

	@Override
	public void computeTarget(int i, State currentState, State newState) throws PrismLangException
	{
		for (Update up : updates.get(i))
			up.update(currentState, newState);
	}

	@Override
	public double getProbability(int i)
	{
		return probability.get(i);
	}

	@Override
	public double getProbabilitySum()
	{
		double sum = 0.0;
		for (double d : probability)
			sum += d;
		return sum;
	}

	@Override
	public int getIndexByProbabilitySum(double x)
	{
		int i, n;
		double d;
		n = size();
		d = 0.0;
		for (i = 0; x >= d && i < n; i++) {
			d += probability.get(i);
		}
		return i - 1;
	}

	@Override
	public void checkValid(ModelType modelType) throws PrismException
	{
		// Currently nothing to do here:
		// Checks for bad probabilities/rates done earlier.
	}
	
	@Override
	public void checkForErrors(State currentState, VarList varList) throws PrismException
	{
		int i, n;
		n = size();
		for (i = 0; i < n; i++) {
			for (Update up : updates.get(i))
				up.checkUpdate(currentState, varList);
		}
	}
	
	@Override
	public String toString()
	{
		int i, n;
		boolean first = true;
		String s = "";
		n = size();
		for (i = 0; i < n; i++) {
			if (first)
				first = false;
			else
				s += " + ";
			s += getProbability(i) + ":" + updates.get(i);
		}
		return s;
	}
	
	private void eventProduct(ChoiceListFlexi ch) throws PrismException {
		for ( int i = 0; i < ch.size() ; ++i) {
			for (int j = 0; j < size()  ; ++j) {
				if (getEventIdent(j) == null ) {
					// this is a slave - so try to obtain a master
					setEventIdent(j, ch.getEventIdent(i));
				} else {
					if (ch.getEventIdent(i) == null) {
						// this is a master synchronizing with a slave - do nothing
					} else {
						// both are the masters - check that their synchronization is allowed
						if (isExponential(getEventIdent(j)) && isExponential(ch.getEventIdent(i)) && this.expSyncBackwardCompatible) {
							// the two masters synchronize into a product event
							String productEventName = "<[" + getEventIdent(j) + "]PRODUCT_WITH[" + ch.getEventIdent(i) + "]>";
							if (!isExponential(productEventName)) { 
								// their product even does not exist yet - create it
								DistributionList distrList = getModulesFile().getDistributionList();
								int distrIndexThis = getDistributionIndex(getEvent(getEventIdent(j)).getDistributionName());
								int distrIndexOther = getDistributionIndex(getEvent(ch.getEventIdent(i)).getDistributionName());
								String distrNameThis = distrList.getDistributionName(distrIndexThis);
								String distrNameOther = distrList.getDistributionName(distrIndexOther);
								String productDistrName = "<[" + distrNameThis + "]PRODUCT_WITH[" + distrNameOther + "]>";
								ExpressionIdent productDistrNameIdent = new ExpressionIdent(productDistrName);
								distrList.addDistribution(
										productDistrNameIdent,
										new ExpressionBinaryOp(
												ExpressionBinaryOp.TIMES,
												distrList.getFirstParameter(distrIndexThis),
												distrList.getFirstParameter(distrIndexOther)),
										null,
										TypeDistributionExponential.getInstance());
								Module module = getEvent(getEventIdent(j)).getParent();
								module.addEvent(new Event(
										new ExpressionIdent(productEventName),
										productDistrNameIdent));
							} 
							// assign this choicelist to the product event
							setEventIdent(j, productEventName);
						} else {
							// synchronization of these masters is not allowed
							if (!isExponential(getEventIdent(j)) || !isExponential(ch.getEventIdent(i))) {
								throw new PrismException("Synchronizing events \"" + getEventIdent(i) + "\" and \"" + ch.getEventIdent(j) + "\" at least one of which is not exponentially distributed!");
							} else {
								throw new PrismException("Synchronizing events \"" + getEventIdent(i) + "\" and \"" + ch.getEventIdent(j) + "\" that are exponentially distributed, but flag ExpSyncBackwardCompatible is false!");
							}
						}
						
					}
				}
			}
		}
	}
	
	/** returns true if this event is exponentially distributed and exists, else false */
	private boolean isExponential(String eventName) {
		Event event = getEvent(eventName);
		if (event == null) {
			return false;
		}
		DistributionList distributionList = getModulesFile().getDistributionList();
		int distrIndex = distributionList.getDistributionIndex(event.getDistributionName());
		return (distributionList.getDistributionType(distrIndex) instanceof TypeDistributionExponential);
	}
	
	/**
	 * Assumes that all the choices are from the same modulesFile and that there is at least one choice
	 * @return ModulesFile the choices originate from
	 */
	private ModulesFile getModulesFile() {
		// beautiful
		return updates.get(0).get(0).getParent().getParent().getParent().getParent();
	}
	
	/**
	 * @param eventName
	 * @return Event of name eventName from within getModulesFile() if it exists, else null
	 */
	private Event getEvent(String eventName) {
		return getModulesFile().getEvent(eventName);
	}
	
	/**
	 * 
	 * @param distributionName
	 * @return Index of distribution of name distributionName from within getModulesFile() if it exists, else -1
	 */
	private int getDistributionIndex(String distributionName) {
		DistributionList distributionList = getModulesFile().getDistributionList();
		return distributionList.getDistributionIndex(distributionName);
	}
}
