//==============================================================================
//	
//	Copyright (c) 2018-
//	Authors:
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

package explicit;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import explicit.rewards.ACTMCRewardsSimple;
import prism.PrismException;

/**
 * Class for storage and computation of potato-related data for ACTMCs.
 * <br>
 * Potato is a subset of states of an ACTMC in which a given event is active.
 * <br><br>
 * This data is fundamental for ACTMC model checking methods based on reduction
 * of ACTMC to CTMC. The reduction works by pre-computing the expected behavior
 * (rewards, spent time, resulting distribution...) occurring between
 * entering and leaving a potato. Then, these expected values are used in
 * regular CTMC/DTMC model checking methods.
 */
public class ACTMCPotatoData
{
	/** ACTMC model this data is associated with */
	private ACTMCSimple actmc;
	/** specific event of {@code actmc} this data is associated with */
	private GSMPEvent event;
	/** Reward structure of the {@code actmc}. May be null. */
	private ACTMCRewardsSimple rewards;
	/** termination epsilon (i.e. when probability gets smaller than this, stop) */
	private double error;
	
	/**
	 * Set of states that belong to the potato.
	 * <br>
	 * I.e. such states of {@code actmc} where {@code event} is active.
	 */
	private Set<Integer> potato = new HashSet<Integer>((int)Math.round(event.getActive().cardinality() * 1.5));
	/** 
	 * Subset of potato states that are acting as entrances into the potato.
	 * <br>
	 * I.e. such states of {@code actmc} where {@code event} is active,
	 * and at the same time they are:
	 * <br>
	 * 1) reachable in a single exponential transition
	 * from a state where {@code event} is not active, or
	 * <br>
	 * 2) reachable as a self-loop of {@code event}.
	 */
	private Set<Integer> entrances = new HashSet<Integer>();
	/**
	 * Set of states that are successors of the potato states.
	 * <br>
	 * I.e. the states of the {@code actmc} that are:
	 * <br>
	 * 1) outside the potato and reachable from within
	 * the potato in a single transition, or
	 * <br>
	 * 2) inside the potato and reachable from within
	 * the potato as a self-loop of {@code event}.
	 */
	private Set<Integer> successors = new HashSet<Integer>();
	private boolean statesComputed = false;
	
	/**
	 * Uniformized DTMC making up the part of {@code actmc} such that it only
	 * contains states that are the union of {@code potato} and {@code successors}.
	 */
	private DTMCUniformisedSimple potatoDTMC = null;
	/** Mapping from the state indices of {@code actmc} (K) to {@code potatoDTMC} (V)*/
	private Map<Integer, Integer> ACTMCtoDTMC = new HashMap<Integer, Integer>();
	/** Mapping from the state indices of {@code potatoDTMC} (K) to {@code actmc} (V) */
	private Map<Integer, Integer> DTMCtoACTMC = new HashMap<Integer, Integer>();
	private boolean potatoDTMCComputed = false;
	
	/**
	 * Poisson distribution values for this potato,
	 * computed and stored by class FoxGlynn.
	 */
	private FoxGlynn foxGlynn = null;
	private boolean foxGlynnComputed = false;
	
	/** Mapping of expected accumulated rewards until leaving the potato onto states used to enter the potato */
	private Map<Integer, Double> meanRewards = new HashMap<Integer, Double>();
	private boolean meanRewardsComputed = false;
	
	/** Mapping of expected times spent in the potato onto states used to enter the potato */
	private Map<Integer, Double> meanTimes = new HashMap<Integer, Double>();
	private boolean meanTimesComputed = false;
	
	/** Mapping of expected outcome state probability distributions onto states used to enter the potato */
	private Map<Integer, Distribution> meanDistributions = new HashMap<Integer, Distribution>();
	private Map<Integer, Double[]> meanDistributionsBeforeEvent = new HashMap<Integer, Double[]>();
	private boolean meanDistributionsComputed = false;
	

	/**
	 * The only constructor
	 * @param actmc Associated ACTMC model. Must not be null!
	 * @param event Event belonging to the ACTMC. Must not be null!
	 * @param error Termination epsilon (i.e. when probability gets smaller than this, stop)
	 * @param rewards ACTMC Reward structure. May be null, but calls to {@code getMeanReward()}
	 *        with null reward structure throws an exception!
	 * @throws Exception if the arguments break the above rules
	 */
	public ACTMCPotatoData(ACTMCSimple actmc,
			GSMPEvent event, double error, ACTMCRewardsSimple rewards) throws Exception {
		if (actmc == null || event == null) {
			throw new NullPointerException("ACTMCPotatoData constructor has received a null object!");
		}
		if (!actmc.getEventList().contains(event)) {
			throw new IllegalArgumentException("ACTMCPotatoData received arguments (actmc,event) where event does not belong to actmc!");
		}
		if (error <= 0.0 || error >= 0.5) { // TODO MAJO - can we force the error to be exactly 0? I guess not.
			throw new IllegalArgumentException("ACTMCPotatoData received an inappropriate termination error bound " + error);
		}
		
		this.actmc = actmc;
		this.event = event;
		this.rewards = rewards;
		this.error = error;
	}
	
	/** Gets the actmc model associated with this object */
	public ACTMCSimple getACTMC() {
		return actmc;
	}
	
	/** Gets the event within the model associated with this object */
	public GSMPEvent getEvent() {
		return event;
	}
	
	/**
	 * Gets a list of states that belong to this potato.
	 * I.e. such states of {@code actmc} where {@code event} is active.
	 * <br>
	 * If this is the first call, this method computes it before returning it.
	 */
	public Set<Integer> getPotato() {
		if (!statesComputed) {
			computeStates();
		}
		return potato;
	}
	
	/**
	 * Gets a list of states that are entrances into this potato.
	 * I.e. such states of {@code actmc} where {@code event} is active,
	 * and at the same time they are reachable in a single transition
	 * from a state where {@code event} is not active.
	 * <br>
	 * If this is the first call, this method computes it before returning it.
	 */
	public Set<Integer> getEntrances() {
		if (!statesComputed) {
			computeStates();
		}
		return entrances;
	}
	
	/**
	 * Gets a list of states outside the potato that are reachable
	 * from within the potato in a single transition.
	 * <br>
	 * I.e. the states of the {@code actmc} that are successors of the potato states.
	 * <br>
	 * If this is the first call, this method computes it before returning it.
	 */
	public Set<Integer> getSuccessors() {
		if (!statesComputed) {
			computeStates();
		}
		return successors;
	}
	
	/**
	 * Gets a uniformized DTMC making up the part of the {@code actmc} such that it
	 * only contains states that are the union of {@code potato} and {@code successors}.
	 * It is a sub-model mimicking the potato behavior of the underlying DTMC.
	 * <br>
	 * WARNING: this DTMC uses a different state indexing to that of the {@code actmc}.
	 * Use maps from {@code getMapCTMCtoACTMC()} and {@code getMapACTMCtoCTMC()}.
	 * <br>
	 * If this is the first call, this method computes them before returning it.
	 */
	public DTMCUniformisedSimple getPotatoCTMC() {
		if (!potatoDTMCComputed) {
			computePotatoDTMC();
		}
		return potatoDTMC;
	}
	
	/**
	 * Gets a mapping from the state indices of {@code actmc} to {@code potatoDTMC}.
	 * I.e. {@code actmc} indices are keys, and {@code potatoDTMC} are values.
	 * <br>
	 * This is a reverse mapping of {@code getMapDTMCtoACTMC()}.
	 * <br>
	 * If this is the first call, this method computes them before returning it.
	 */
	public Map<Integer, Integer> getMapACTMCtoDTMC() {
		if (!potatoDTMCComputed) {
			computePotatoDTMC();
		}
		return ACTMCtoDTMC;
	}
	
	/**
	 * Gets a mapping from the state indices of {@code potatoDTMC} to {@code actmc}.
	 * I.e. {@code potatoDTMC} indices are keys, and {@code actmc} are values.
	 * <br>
	 * This is a reverse mapping of {@code getMapACTMCtoDTMC()}.
	 * <br>
	 * If this is the first call, this method computes them before returning it.
	 */
	public Map<Integer, Integer> getMapDTMCtoACTMC() {
		if (!potatoDTMCComputed) {
			computePotatoDTMC();
		}
		return DTMCtoACTMC;
	}
	
	/**
	 * Gets a map where the keys are entrances into the potato, and
	 * the values are mean accumulated rewards until leaving the potato
	 * if entered from state {@code key}.
	 * <br>
	 * If this is the first call, this method computes it before returning it.
	 */
	public Map<Integer, Double> getMeanRewards() throws PrismException {
		if (!meanRewardsComputed) {
			computeMeanRewards();
		}
		return meanRewards;
	}
	
	/**
	 * Gets a map where the keys are entrances into the potato, and
	 * the values are mean times until leaving the potato
	 * if entered from state {@code key}.
	 * <br>
	 * If this is the first call, this method computes it before returning it.
	 */
	public Map<Integer, Double> getMeanTimes() throws PrismException {
		if (!meanTimesComputed) {
			computeMeanTimes();
		}
		return meanTimes;
	}
	
	/**
	 * Gets a map where the keys are entrances into the potato, and
	 * the values are mean outcome probability distributions after leaving the potato
	 * if entered from state {@code key}.
	 * <br>
	 * If this is the first call, this method computes it before returning it.
	 */
	public Map<Integer, Distribution> getMeanDistributions() throws PrismException {
		if (!meanDistributionsComputed) {
			computeMeanDistributions();
		}
		return meanDistributions;
	}
	
	private void computeStates() {
		computePotato();
		computeEntrances();
		computeSuccessors();
		statesComputed = true;
	}
	
	private void computePotato() {
		BitSet potatoBs = event.getActive();
		for (int ps = potatoBs.nextSetBit(0); ps >= 0; ps = potatoBs.nextSetBit(ps+1)) {
			potato.add(ps);
		}
	}
	
	/** Assumes that {@code computePotato()} has been called already */
	private void computeEntrances() {
		List<Integer> candidateEntrances = new ArrayList<Integer>(potato);
		
		// For each state of the ACTMC...
		for (int s = 0 ; s < actmc.getNumStates() ; ++s) {
			// ...if it does not belong to the potato...
			if (actmc.getActiveEvent(s) != event) {
				// ...check whether it has an exponential transition into the potato.
				Distribution distr = actmc.getTransitions(s);
				for (Iterator<Integer> iter = candidateEntrances.iterator() ; iter.hasNext();) {
					int ps = iter.next();
					if (distr.get(ps) > 0.0) {
						entrances.add(ps);
						iter.remove();
					}
				}
				if (candidateEntrances.isEmpty()) {
					break;
				}
			}
		}
		
		// Lastly, check whether the event has a self-loop.
		for (int ps : potato) {
			Distribution distr = event.getTransitions(ps);
			for (int ps2 : potato) {
				if (distr.getSupport().contains(ps2)) {
					entrances.add(ps2);
				}
			}
			// Also, since I am iterating over the event distributions,
			// I might as well find the event distribution successors.
			successors.addAll(distr.getSupport());
		}
	}
	
	/** Assumes that {@code computePotato()} and {@code computeEntrances()}
	 *  have been called already */
	private void computeSuccessors() {
		for (int ps : potato) {
			Set<Integer> support = new HashSet<Integer>(actmc.getTransitions(ps).getSupport());
			support.removeIf( s -> potato.contains(s));
			successors.addAll(support);
		}
	}
	
	private void computePotatoDTMC() {
		if (!statesComputed) {
			computeStates();
		}
		
		// Identify the set of relevant states and declare the new CTMC
		Set<Integer> potatoACTMCStates = new HashSet<Integer>(potato);
		potatoACTMCStates.addAll(successors);
		CTMCSimple potatoCTMC = new CTMCSimple(potatoACTMCStates.size());
		
		// Since the states of the new CTMC are indexed from 0,
		// we need a mapping from the original ACTMC to the new DTMC,
		// and vice-versa.
		{
			int index = 0;
			for (int s : potatoACTMCStates) {
				ACTMCtoDTMC.put(s, index);
				DTMCtoACTMC.put(index, s);
				++index;
			}
		}
		
		double uniformizationRate = actmc.getDefaultUniformisationRate();
		// Construct the transition matrix of the new CTMC
		for (int s : potatoACTMCStates) {
			if (potato.contains(s)) {
				// If the state is a part of the potato, retain the distribution as is
				Distribution distr = actmc.getTransitions(s);
				Set<Integer> support = new HashSet<Integer>(distr.getSupport());
				support.removeIf( state -> !potatoACTMCStates.contains(state) );
				for ( int state : support) {
					potatoCTMC.addToProbability(ACTMCtoDTMC.get(s), ACTMCtoDTMC.get(state), distr.get(state));
				}
			} else {
				// Else the state is a potato successor, so make it absorbing.
				potatoCTMC.addToProbability(ACTMCtoDTMC.get(s), ACTMCtoDTMC.get(s), uniformizationRate);
			}
		}
		
		// convert the CTMC to a DTMC and store the DTMC
		potatoCTMC.uniformise(uniformizationRate);
		double q = potatoCTMC.getMaxExitRate();
		potatoDTMC = (DTMCUniformisedSimple) potatoCTMC.buildImplicitUniformisedDTMC(q);
		
		potatoDTMCComputed = true;
	}
	
	private void computeFoxGlynn() throws PrismException {
		if (!potatoDTMCComputed) {
			computePotatoDTMC();
		}
		// TODO MAJO - should this foxGynn have accuracy =error, or better ?
		
		// Using class FoxGlynn to pre-compute the Poisson distribution.
		// Different approach is required for each distribution type.
		switch (event.getDistributionType().getEnum()) {
		case DIRAC:
			double fgRate = potatoDTMC.getUniformisationRate() * event.getFirstParameter();
			foxGlynn = new FoxGlynn(fgRate, 1e-300, 1e+300, error);
			break;
		case ERLANG:
			throw new UnsupportedOperationException("ACTMCPotatoData does not yet support the Erlang distribution!");
			// TODO MAJO - implement erlang distributed event support
			//break;
		case EXPONENTIAL:
			throw new PrismException("ACTMCPotatoData received an event with exponential distribution!");
			// TODO MAJO - implement exponentially distributed event support
			//break;
		case UNIFORM:
			throw new UnsupportedOperationException("ACTMCPotatoData does not yet support the uniform distribution!");
			// TODO MAJO - implement uniformly distributed event support
			//break;
		case WEIBULL:
			throw new UnsupportedOperationException("ACTMCPotatoData does not yet support the Weibull distribution!");
			// TODO MAJO - implement weibull distributed event support
			//break;
		default:
			throw new PrismException("ACTMCPotatoData received an event with unrecognized distribution!");
		}
		if (foxGlynn.getRightTruncationPoint() < 0) {
			throw new PrismException("Overflow in Fox-Glynn computation of the Poisson distribution!");
		}
		
		foxGlynnComputed = true;
	}
	
	/**
	 * For all potato entrances, computes the expected time spent within the potato
	 * before leaving the potato, having entered from a particular entrance.
	 * This is computed using the expected cumulative reward with reward 1
	 * for states within the potato, and with a time bound given by the potato event.
	 */
	private void computeMeanTimes() throws PrismException {
		if (!foxGlynnComputed) {
			computeFoxGlynn();
		}
		
		double uniformizationRate = potatoDTMC.getUniformisationRate();
		int numStates = potatoDTMC.getNumStates();
		
		// Prepare the FoxGlynn data
		int left = foxGlynn.getLeftTruncationPoint();
		int right = foxGlynn.getRightTruncationPoint();
		double[] weights = foxGlynn.getWeights().clone();
		double totalWeight = foxGlynn.getTotalWeight();
		for (int i = left; i <= right; i++) {
			weights[i - left] /= totalWeight;
		}
		for (int i = left+1; i <= right; i++) {
			weights[i - left] += weights[i - 1 - left];
		}
		for (int i = left; i <= right; i++) {
			weights[i - left] = (1 - weights[i - left]) / uniformizationRate;
		}
		
		// Prepare solution arrays
		double[] soln = new double[numStates];
		double[] soln2 = new double[numStates];
		double[] result = new double[numStates];
		double[] tmpsoln = new double[numStates];

		// Initialize the solution array by assigning reward
		// 1 to each state within the potato, and 0 to all others.
		for (int i = 0; i < numStates; i++) {
			if (potato.contains(DTMCtoACTMC.get(i))) {
				soln[i] = 1;
			} else {
				soln[i] = 0;
			}
		}

		// do 0th element of summation (doesn't require any matrix powers)
		result = new double[numStates];
		if (left == 0) {
			for (int i = 0; i < numStates; i++) {
				result[i] += weights[0] * soln[i];
			}
		} else {
			for (int i = 0; i < numStates; i++) {
				result[i] += soln[i] / uniformizationRate;
			}
		}

		// Start iterations
		int iters = 1;
		while (iters <= right) {
			// Matrix-vector multiply
			potatoDTMC.mvMult(soln, soln2, null, false);
			// Swap vectors for next iter
			tmpsoln = soln;
			soln = soln2;
			soln2 = tmpsoln;
			// Add to sum
			if (iters >= left) {
				for (int i = 0; i < numStates; i++)
					result[i] += weights[iters - left] * soln[i];
			} else {
				for (int i = 0; i < numStates; i++)
					result[i] += soln[i] / uniformizationRate;
			}
			iters++;
		}
		
		// We are done. 
		// Store the values for the entrances using the original indexing.
		for (int entrance : entrances) {
			meanTimes.put(entrance, result[ACTMCtoDTMC.get(entrance)]);
		}
		
		meanTimesComputed = true;
	}
	
	/**
	 * For all potato entrances, computes the expected distributions
	 * on states after leaving the potato, having entered from a particular entrance.
	 * I.e., on average, where does the ACTMC end up when it happens to enter a potato.
	 */
	private void computeMeanDistributions() throws PrismException {
		if (!foxGlynnComputed) {
			computeFoxGlynn();
		}
		
		int numStates = potatoDTMC.getNumStates();

		// Prepare the FoxGlynn data
		int left = foxGlynn.getLeftTruncationPoint();
		int right = foxGlynn.getRightTruncationPoint();
		double[] weights = foxGlynn.getWeights().clone();
		double totalWeight = foxGlynn.getTotalWeight();
		for (int i = left; i <= right; i++) {
			weights[i - left] /= totalWeight;
		}
		
		// Prepare solution arrays
		double[] initDist = new double[numStates];
		double[] soln;
		double[] soln2 = new double[numStates];
		double[] result = new double[numStates];
		double[] tmpsoln = new double[numStates];
		
		for (int entrance : entrances) {
			
			// Build the initial distribution for this potato entrance
			for (int s = 0; s < numStates  ; ++s) {
				if ( s == ACTMCtoDTMC.get(entrance)) {
					initDist[s] = 1;
				} else {
					initDist[s] = 0;
				}
			}
			soln = initDist;

			// Initialize the result array
			for (int i = 0; i < numStates; i++) {
				result[i] = 0.0;
			}

			// If necessary, compute the 0th element of summation
			// (doesn't require any matrix powers)
			if (left == 0) {
				for (int i = 0; i < numStates; i++) {
					result[i] += weights[0] * soln[i];
				}
			}

			// Compute the potatoCTMC solution vector just before the event occurs
			int iters = 1;
			while (iters <= right) {
				// Matrix-vector multiply
				potatoDTMC.vmMult(soln, soln2);
				// Swap vectors for next iter
				tmpsoln = soln;
				soln = soln2;
				soln2 = tmpsoln;
				// Add to sum
				if (iters >= left) {
					for (int i = 0; i < numStates; i++)
						result[i] += weights[iters - left] * soln[i];
				}
				iters++;
			}
			// Store the CTMC solution vector for use by the computeMeanRewards() method
			Double[] resultBeforeEvent = new Double[numStates];
			for(int i = 0; i < numStates ; ++i ) {
				resultBeforeEvent[i] = result[i];
			}
			meanDistributionsBeforeEvent.put(entrance, resultBeforeEvent);
			
			// Lastly, if there is some probability that the potatoDTMC would 
			// still be within the potato at the time of the event occurrence,
			// these probabilities must be redistributed into the successor states
			// using the event-defined distribution on states.
			// (I.e. the actual event behavior is applied)
			for ( int ps : potato) {
				int psIndex = ACTMCtoDTMC.get(ps);
				if (result[psIndex] >= error) { // TODO MAJO - >=error or >0 ???
					Distribution distr = event.getTransitions(ps);
					Set<Integer> distrSupport = distr.getSupport();
					for ( int successor : distrSupport) {
						result[ACTMCtoDTMC.get(successor)] += result[psIndex] * distr.get(successor);
					}
				}
				result[psIndex] = 0;
			}
			
			// We are done. 
			// Convert the result to a distribution with original indexing and store it.
			Distribution resultDistr = new Distribution();
			for (int state : entrances) {
				resultDistr.add(state, result[ACTMCtoDTMC.get(state)]);
				// TODO MAJO - the distribution might not sum to 1 (imprecision)
			}
			meanDistributions.put(entrance, resultDistr);
		}
		meanDistributionsComputed = true;
	}
	
	/**
	 * For all potato entrances, computes the expected reward earned within the potato
	 * before leaving the potato, having entered from a particular entrance.
	 * This is computed using the expected cumulative reward using the ACTMC reward
	 * structure for states within the potato, and with a time bound
	 * given by the potato event. Since this would only be the underlying CTMC behavior,
	 * it is the necessary to apply the potato event behavior as well.
	 * In order to do that, the event transition rewards are weighted by the expected
	 * distribution on states at the time of the occurrence of the event.
	 */
	private void computeMeanRewards() throws PrismException {
		if (!meanDistributionsComputed) {
			computeMeanDistributions();
		}
		
		double uniformizationRate = potatoDTMC.getUniformisationRate();
		int numStates = potatoDTMC.getNumStates();
		
		// Prepare the FoxGlynn data
		int left = foxGlynn.getLeftTruncationPoint();
		int right = foxGlynn.getRightTruncationPoint();
		double[] weights = foxGlynn.getWeights().clone();
		double totalWeight = foxGlynn.getTotalWeight();
		for (int i = left; i <= right; i++) {
			weights[i - left] /= totalWeight;
		}
		for (int i = left+1; i <= right; i++) {
			weights[i - left] += weights[i - 1 - left];
		}
		for (int i = left; i <= right; i++) {
			weights[i - left] = (1 - weights[i - left]) / uniformizationRate;
		}
		
		// Prepare solution arrays
		double[] soln = new double[numStates];
		double[] soln2 = new double[numStates];
		double[] result = new double[numStates];
		double[] tmpsoln = new double[numStates];

		// Initialize the solution array by assigning rewards to the potato states
		for (int i = 0; i < numStates; i++) {
			int index = DTMCtoACTMC.get(i);
			if (potato.contains(index)) {
				soln[i] = rewards.getStateReward(index);
			} else {
				soln[i] = 0;
			}
		}
		//////////////////////////////////////////////
		// TODO MAJO - IMPLEMENT TRANSITION REWARDS!!!
		if (rewards.hasTransitionRewards()) {
			throw new UnsupportedOperationException("ACTMCPotatoData does not yet support transition rewards!");
		}
		// TODO MAJO - IMPLEMENT TRANSITION REWARDS!!!
		//////////////////////////////////////////////

		// do 0th element of summation (doesn't require any matrix powers)
		result = new double[numStates];
		if (left == 0) {
			for (int i = 0; i < numStates; i++) {
				result[i] += weights[0] * soln[i];
			}
		} else {
			for (int i = 0; i < numStates; i++) {
				result[i] += soln[i] / uniformizationRate;
			}
		}

		// Start iterations
		int iters = 1;
		while (iters <= right) {
			// Matrix-vector multiply
			potatoDTMC.mvMult(soln, soln2, null, false);
			// Swap vectors for next iter
			tmpsoln = soln;
			soln = soln2;
			soln2 = tmpsoln;
			// Add to sum
			if (iters >= left) {
				for (int i = 0; i < numStates; i++)
					result[i] += weights[iters - left] * soln[i];
			} else {
				for (int i = 0; i < numStates; i++)
					result[i] += soln[i] / uniformizationRate;
			}
			iters++;
		}
		
		//Now that we have the expected rewards for the underlying CTMC behavior,
		//event behavior is applied as described above.
		for (int entrance : entrances) {
			for (int ps : potato) {
				Map<Integer, Double> rews = rewards.getEventTransitionRewards(ps);
				Set<Integer> rewSet = rews.keySet();
				for (int succ : rewSet) {
					double prob = event.getTransitions(ps).get(succ);
					double weight = meanDistributionsBeforeEvent.get(entrance)[ACTMCtoDTMC.get(ps)];
					double eventRew = rews.get(succ);
					result[ACTMCtoDTMC.get(entrance)] += prob * weight * eventRew;
					// TODO MAJO - uncertain about this
				}
			}
			
			// We are done computing this potato entrance
			// Store the value using the original indexing.
			meanRewards.put(entrance, result[ACTMCtoDTMC.get(entrance)]);
		}
		
		meanRewardsComputed = true;
	}

}