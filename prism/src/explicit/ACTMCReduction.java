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

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ch.obermuhlner.math.big.BigDecimalMath;
import common.BigDecimalUtils;
import explicit.ProbModelChecker.LinEqMethod;
import explicit.rewards.ACTMCRewardsSimple;
import explicit.rewards.MCRewards;
import explicit.rewards.StateRewardsConstant;
import explicit.rewards.StateRewardsSimple;
import prism.Pair;
import prism.PrismComponent;
import prism.PrismDevNullLog;
import prism.PrismException;
import prism.PrismSettings;

/**
 * Class for reduction of ACTMC to equivalent DTMC. (and also their reward structures)
 * <br>
 * This class fulfills similar purpose to class {@code ACTMCPotatoData},
 * but on the scope of the entire ACTMC, whereas the scope of {@code ACTMCPotatoData}
 * only encompasses a single event.
 */
public class ACTMCReduction extends PrismComponent
{
	/** ACTMC model this class is associated with */
	private ACTMCSimple actmc;
	/** Optional reward structure associated with {@code actmc}.
	 *  The CTMC transition rewards are already expected to have been converted to state rewards.
	 *  May be null if rewards are not of interest for given model checking method.*/
	private ACTMCRewardsSimple actmcRew = null;
	/** Optional bitset of target states (for reachability) */
	private BitSet target = null;
	/** If set to true, the generated reward structure will be made for steady-state rewards computation.
	 *  This needs to be true for steady-state type of computations,
	 *  and false for reachability reward computation. */
	private boolean computingSteadyState;
	/** bitset of states of which each element:
	 *  1) either does not belong to any potato (no non-exponential event is active in it),
	 *  2) or it is an entrance to some potato. */
	private BitSet relevantStates;
	/** Map where the keys are string identifiers of the GSMPEvents,
	 *  and the values are corresponding ACTMCPotatoData structures.
	 *  This is useful for fast access and efficient reusage of the ACTMCPotatoData structures.*/
	private Map<String, ACTMCPotato> pdMap;
	/** DTMC equivalent to {@code actmc} eventually generated by this class.
	 *  Initially null.*/
	private DTMCSimple dtmc = null;
	/** Rewards for {@code dtmc} equivalent to {@code actmcRew} eventually generated by this class.
	 *  Initially null.*/
	private MCRewards dtmcRew = null;
	
	/** Requested total epsilon accuracy for subsequent model checking.
	 *  This is an option from the parent prismComponent settings.
	 *  (termCritParam / Termination epsilon) */
	private BigDecimal epsilon;
	/** If this is true, kappa should be computed for the model.
	 *  This is an option from parent prismComponent settings.
	 *  (PRISM_ACTMC_COMPUTE_KAPPA / Compute precision for ACTMC (GSMP) reduction) */
	private boolean computeKappa;
	/** Constant allowed error kappa used if {@code computeKappa} is false.
	 *  This is an option from parent prismComponent settings. 
	 *  (PRISM_ACTMC_CONSTANT_KAPPA_DECIMAL_DIGITS / ACTMC (GSMP) reduction constant precision (decimal digits) */
	private BigDecimal constantKappa;
	/** Default first stage accuracy for computing kappa */
	private static final BigDecimal pre_epsilon = new BigDecimal("0.1");
	
	
	/**
	 * The only constructor
	 * @param actmc Associated ACTMC model. Must not be null!
	 * @param actmcRew Optional reward structure associated with {@code actmc}. May be null.
	 * @param target Optional bitset of target states (if doing reachability). May be null.
	 * @param computingSteadyState This should be true for steady-state reward type
	 *  						   of computations, and false for reachability rewards.
	 * @param parent PrismComponent, presumably a model checker.
	 * Used to obtain current settings.
	 * @throws Exception if the arguments break the above rules
	 */
	public ACTMCReduction(ACTMCSimple actmc, ACTMCRewardsSimple actmcRew, BitSet target, boolean computingSteadyState, PrismComponent parent) throws PrismException {
		super(parent);
		if (actmc == null) {
			throw new NullPointerException("ACTMCReduction constructor has received a null actmc!");
		}
		if (parent == null) {
			throw new NullPointerException("ACTMCReduction constructor has received a null parent (model checker object)!");
		}
		this.actmc = actmc;
		this.actmcRew = actmcRew;
		this.target = target;
		if (this.target == null) {
			this.target = new BitSet(actmc.getNumStates());
		}
		this.computingSteadyState = computingSteadyState;
		this.epsilon = new BigDecimal(this.getSettings().getDouble(PrismSettings.PRISM_TERM_CRIT_PARAM));
		this.computeKappa = this.getSettings().getBoolean(PrismSettings.PRISM_ACTMC_COMPUTE_KAPPA);
		this.constantKappa = BigDecimalUtils.allowedError(this.getSettings().getInteger(PrismSettings.PRISM_ACTMC_CONSTANT_KAPPA_DECIMAL_DIGITS));
		this.pdMap = createPotatoDataMap(this.actmc, this.actmcRew, this.target);
		this.relevantStates = new BitSet(actmc.getNumStates());
		setRelevantStates();
		
	}
	
	/**
	 * Get a DTMC fully equivalent to {@code actmc}.
	 * Computed DTMC is accurate up to error {@literal kappa} computed by this class.
	 */
	public DTMCSimple getDTMC() throws PrismException {
		if (dtmc == null) {
			computeEquivalentDTMC();
		}
		return dtmc;
	}
	
	/**
	 * Get a DTMC reward structure for {@code dtmc} fully equivalent to {@code actmc}.
	 * Computed values are accurate up to error {@literal kappa} computed by this class.
	 */
	public MCRewards getDTMCRew() throws PrismException {
		if (dtmc == null) {
			computeEquivalentDTMC();
		}
		if (dtmcRew == null) {
			computeEquivalentDTMCRew();
		}
		return dtmcRew;
	}
	
	/**
	 * Get {@code ACTMCPotatoData} used to create equivalent DTMC.
	 * Computed values are accurate up to error {@literal kappa} computed by this class.
	 */
	public Map<String, ACTMCPotato> getPotatoData() throws PrismException {
		if (dtmc == null) {
			computeEquivalentDTMC();
		}
		return pdMap;
	}
	
	private void computeEquivalentDTMC() throws PrismException {
		if (computeKappa && !pdMap.isEmpty()) {
			setKappa(BigDecimalUtils.min(computeKappa(), constantKappa));
		} else {
			setKappa(constantKappa);
		}
		dtmc = constructUniformizedDTMC();
	}
	
	private void computeEquivalentDTMCRew() throws PrismException {
		if (dtmc == null) {
			computeEquivalentDTMC();
		}
		dtmcRew = constructDTMCRew(dtmc, computingSteadyState);
	}
	
	/**
	 * Computes and sets the bitset of relevant states {@code relevantStates}.
	 * The {@code pdMap} must already be correctly initialised!
	 */
	private void setRelevantStates() {
		Set<Integer> potatoStates = new HashSet<Integer>();
		Set<Integer> entranceStates = new HashSet<Integer>();
		for (Map.Entry<String, ACTMCPotato> pdEntry : pdMap.entrySet()) {
			potatoStates.addAll(pdEntry.getValue().getPotato());
			entranceStates.addAll(pdEntry.getValue().getEntrances());
		}
		
		relevantStates.set(0, actmc.getNumStates());
		for (int ps : potatoStates) {
			relevantStates.set(ps, false);
		}
		for (int es : entranceStates) {
			relevantStates.set(es, true);
		}
	}
	
	/**
	 * Assigns {@code kappa} to all ACTMCPotatoData within {@code pdMap}.
	 * <br>
	 * I.e. next ACTMCPotatoData computations will be with precision kappa.
	 * @param kappa BigDecimal allowed error bound
	 */
	private void setKappa(BigDecimal kappa) {
		for (Map.Entry<String, ACTMCPotato> pdEntry : pdMap.entrySet()) {
			pdEntry.getValue().setKappa(kappa);
		}
	}
	
	/**
	 * Computes the kappa error bound such that any model checking
	 * done on a thusly created {@code dtmc} is guaranteed to be accurate
	 * within allowed error {@code epsilon}.
	 */
	private BigDecimal computeKappa() throws PrismException {
		MathContext mc;
		BigDecimal n = new BigDecimal(actmc.getNumStates() - target.cardinality()); // amount of non-target states
		
		// derive kappaSteps and kappaTR from a rough estimate of the model structure
		BigDecimal kappaSteps;
		BigDecimal kappaTR; {
			Pair<Double, Double> minProb_maxRew = compute_minProb_maxRew();
			double minProb = minProb_maxRew.getKey();
			double maxRew = 0;
			if (actmcRew != null) {
				maxRew = minProb_maxRew.getValue();
			}
			if (maxRew == 0) { // This deals with situations where there are no rewards.
				maxRew = 1;
			}
			BigDecimal baseKappaOne = new BigDecimal(minProb / 2);
			BigDecimal baseKappaTwo = new BigDecimal(Math.min(baseKappaOne.doubleValue(), maxRew));
			BigDecimal maxExpectedSteps; {
				int maxExpectedStepsPrecision = 3 + BigDecimalUtils.decimalDigits(baseKappaOne) * n.intValue() * 2;
				mc = new MathContext(maxExpectedStepsPrecision, RoundingMode.HALF_UP);
				maxExpectedSteps = n.divide(BigDecimalMath.pow(baseKappaOne, n, mc), mc);
			}
			BigDecimal maxExpectedTR = maxExpectedSteps.multiply(new BigDecimal(maxRew));
			mc = new MathContext((mc.getPrecision() * 2) + (int) maxRew, RoundingMode.HALF_UP);
			BigDecimal b = BigDecimal.ONE.divide(new BigDecimal("2.0").multiply(maxExpectedSteps).multiply(n), mc);
			/* kappaSteps derivation*/ {
				BigDecimal c = pre_epsilon.divide(new BigDecimal("2.0").multiply(maxExpectedSteps).multiply(maxExpectedSteps.multiply(n).add(BigDecimal.ONE)), mc);
				kappaSteps = BigDecimalUtils.min(baseKappaOne, BigDecimalUtils.min(b, c));
			}
			/* kappaTR derivation*/ {
				BigDecimal c = pre_epsilon.divide(new BigDecimal("2.0").multiply(maxExpectedSteps).multiply(maxExpectedTR.multiply(n).add(BigDecimal.ONE)), mc);
				kappaTR = BigDecimalUtils.min(baseKappaTwo, BigDecimalUtils.min(b, c));
			}
		}

		// derive a more precise estimate of the bounds on the amount of steps and time
		BigDecimal minTime = new BigDecimal(Double.MAX_VALUE);
		BigDecimal maxTime = new BigDecimal(Double.MIN_VALUE);
		BigDecimal maxSteps; {
			setKappa(kappaSteps);
			DTMCSimple kappaOneDTMC = constructUniformizedDTMC();
			MCRewards kappaOneDTMCRew = new StateRewardsConstant(1 / kappaOneDTMC.uniformizationRate);
			DTMCModelChecker mc1 = new DTMCModelChecker(this);
			mc1.termCritParam = pre_epsilon.doubleValue();
			mc1.linEqMethod = LinEqMethod.GAUSS_SEIDEL; // TODO MAJO - maybe this can go away, but reliability is priority!
			mc1.setLog(new PrismDevNullLog()); // mute the reachability computation log messages
			for (int s = relevantStates.nextSetBit(0); s >= 0; s = relevantStates.nextSetBit(s+1)) {
				boolean isEntranceTarget = target.get(s);
				target.set(s);
				ModelCheckerResult kappaOneTR = mc1.computeReachRewards(kappaOneDTMC, kappaOneDTMCRew, target);
				target.set(s, isEntranceTarget);
				
				Pair<Double, Double> minMax = findMinMax(kappaOneTR.soln);
				if (!minMax.first.isNaN()) { 
					BigDecimal min = (new BigDecimal(minMax.first)).subtract(pre_epsilon);
					if (minTime.compareTo(min) > 0) {
						minTime = min;
					}
				}
				if (!minMax.second.isNaN()) {
					BigDecimal max = (new BigDecimal(minMax.second)).add(pre_epsilon);
					if (maxTime.compareTo(max) < 0) {
						maxTime = max;
					}
				}
			}
			if (minTime.compareTo(new BigDecimal(Double.MAX_VALUE)) == 0) {
				minTime = BigDecimal.ONE;
			}
			if (maxTime.compareTo(new BigDecimal(Double.MIN_VALUE)) == 0) {
				maxTime = BigDecimal.ONE;
			}
			maxSteps = maxTime.multiply(new BigDecimal(kappaOneDTMC.uniformizationRate), mc);
		}
		
		// derive a more precise estimate of the upper bound on the total reward
		BigDecimal maxTR = new BigDecimal(Double.MIN_VALUE); {
			setKappa(kappaTR);
			DTMCSimple kappaTwoDTMC = constructUniformizedDTMC();
			MCRewards kappaTwoDTMCRew = constructUniformizedDTMCRew(kappaTwoDTMC);
			DTMCModelChecker mc2 = new DTMCModelChecker(this);
			mc2.termCritParam = pre_epsilon.doubleValue();
			mc2.linEqMethod = LinEqMethod.GAUSS_SEIDEL; // TODO MAJO - maybe this can go away, but reliability is priority!
			mc2.setLog(new PrismDevNullLog()); // mute the reachability computation log messages
			for (int s = relevantStates.nextSetBit(0); s >= 0; s = relevantStates.nextSetBit(s+1)) {
				boolean isEntranceTarget = target.get(s);
				target.set(s);
				ModelCheckerResult kappaTwoTR = mc2.computeReachRewards(kappaTwoDTMC, kappaTwoDTMCRew, target);
				target.set(s, isEntranceTarget);
				
				Pair<Double, Double> minMax = findMinMax(kappaTwoTR.soln);
				if (!minMax.second.isNaN()) {
					BigDecimal max = (new BigDecimal(minMax.second)).add(pre_epsilon);
					if (maxTR.compareTo(max) < 0) {
						maxTR = max;
					}
				}
			}
			if (maxTR.compareTo(new BigDecimal(Double.MIN_VALUE)) == 0) {
				maxTR = BigDecimal.ONE;
			}
		}
		
		// use the previous values to derive the actual kappa allowed error bound
		BigDecimal kappa;
		if (computingSteadyState) {
			// kappa for Mean Payoff computations
			BigDecimal wMax = BigDecimalUtils.max(maxTR, maxTime);
			BigDecimal a = (minTime.multiply(minTime, mc)).multiply(epsilon.divide(n, mc), mc);
			BigDecimal b = wMax.multiply(epsilon.divide(n, mc).add(new BigDecimal("2.0"), mc), mc).multiply(n.multiply(wMax, mc).add(BigDecimal.ONE, mc), mc);
			BigDecimal aDivb = a.divide(b, mc);
			
			kappa = BigDecimalUtils.min(aDivb, BigDecimalUtils.min(kappaSteps, kappaTR));
		} else {
			// kappa for Reachability Reward computation
			BigDecimal aAccurate = BigDecimal.ONE.divide(new BigDecimal("2.0").multiply(n).multiply(maxSteps), mc);
			BigDecimal bAccurate = epsilon.divide(new BigDecimal("2.0").multiply(maxSteps).multiply(maxTR.multiply(n).add(BigDecimal.ONE)), mc);
			
			kappa = BigDecimalUtils.min(kappaSteps, BigDecimalUtils.min(kappaTR, BigDecimalUtils.min(aAccurate, bAccurate)));
		}
		
		// Adjust kappa for termination epsilon (just to be safe)
		kappa = kappa.multiply(epsilon, mc);
		return kappa;
	}
	
	/**
	 * Computes the lowest probability that could be present within a {@code dtmc}
	 * created from {@code actmc} and the highest reward that could be present
	 * within an equivalent reward structure for {@code dtmc}.
	 * @return a pair where the key is the minimum probability, and the value is the maximum reward
	 */
	private Pair<Double, Double> compute_minProb_maxRew() throws PrismException {
		final double kappa = 1.0e-20;
		final BigDecimal kappaBD = new BigDecimal(kappa);
		setKappa(kappaBD);
		
		// construct dtmc and dtmcRew
		DTMCSimple dtmc = constructUniformizedDTMC();
		StateRewardsSimple rewards = constructUniformizedDTMCRew(dtmc);
		
		// obtain the values and adjust them for potential kappa error
		BitSet relevantStates = new BitSet(actmc.getNumStates());
		relevantStates.or(this.relevantStates);
		relevantStates.andNot(target);
		double minProb = dtmc.getMinimumProbability(relevantStates) + kappa;
		double maxRew = rewards.getMax(relevantStates) + kappa; // TODO MAJO - shouldnt this be minus kappa?
		
		return new Pair<Double, Double>(minProb, maxRew);
	}
	
	/**
	 * Finds the maximum and minimum element of the array, but only considers {@code relevantStates}.
	 * WARNING: The returned value must be checked for NaN !!!
	 * @return A pair where the first value (K) is the minimum positive element
	 *  	   and the second (V) is the maximum positive element, where: 
	 *  	   <br> MINIMUM= <br>
	 *         If relevant results are given, then their smallest positive element. <br>
	 * 		   If the smallest positive element of the relevant results is {@literal Infinity}, then NaN. <br>
	 * 		   If the smallest positive element of the relevant results is 0, NaN. <br>
	 * 		   If no relevant results are given, then NaN.
	 *  	   <br> MAXIMUM= <br>
	 *         If relevant results are given, then their greatest positive element. <br>
	 * 		   If the greatest positive element of the relevant results is {@literal Infinity}, then NaN. <br>
	 * 		   If the greatest positive element of the relevant results is 0, then NaN. <br>
	 * 		   If no relevant results are given, then NaN. <br>
	 */
	private Pair<Double, Double> findMinMax(double[] array) {
		
		// find min/max of the relevant states
		double max = Double.MIN_VALUE;
		double min = Double.MAX_VALUE;
		for (int s = relevantStates.nextSetBit(0); s >= 0; s = relevantStates.nextSetBit(s+1)) {
			if (array[s] > max) {
				max = array[s];
			}
			if (array[s] < min && array[s] > 0) {
				min = array[s];
			}
		}
		
		// This deals with strange behavior of reachability rewards when there are no rewards.
		if (max == Double.MIN_VALUE) {
			max = Double.NaN; 
		}
		if (min == Double.MAX_VALUE) {
			min = Double.NaN; 
		}
		
		// This deals with strange behavior of reachability rewards when entrance is the initial state.
		if (Double.isInfinite(max)) {
			max = Double.NaN; 
		}
		if (Double.isInfinite(min)) {
			min = Double.NaN; 
		}
		
		// This should never happen.
		if (max == 0) {
			max = Double.NaN;
		}
		if (min == 0) {
			min = Double.NaN;
		}
		
		return new Pair<Double, Double>(min, max);
	}
	
	/**
	 * Uses {@code actmc} and current {@code pdMap} to construct
	 * equivalent uniformized {@code dtmc}. The DTMC is uniformized in accordance to how much
	 * time is spent within each potato having entered from a particular entrance.
	 * @return Uniformized fully {@code dtmc} equivalent to {@code actmc} according to the current {@code pdMap}
	 */
	private DTMCSimple constructUniformizedDTMC() throws PrismException {
		CTMCSimple ctmc = new CTMCSimple(actmc);
		double uniformizationRate = ctmc.getMaxExitRate();
		
		for (Map.Entry<String, ACTMCPotato> pdEntry : pdMap.entrySet()) {
			ACTMCPotato potatoData = pdEntry.getValue();
			Map<Integer, Distribution> meanTimesWithinPotato = potatoData.getMeanTimes();
			Map<Integer, Distribution> meanDistrs = potatoData.getMeanDistributions();
			Set<Integer> potatoEntrances = potatoData.getEntrances();
			
			for (int entrance : potatoEntrances) {
				// compute the rate
				Distribution potatoTimeDistr = meanTimesWithinPotato.get(entrance);
				double theta = potatoTimeDistr.sum();
				double meanRateWithinPotato = 1 / theta;
				if ((meanRateWithinPotato) > uniformizationRate) {
					uniformizationRate = meanRateWithinPotato;
				}
				
				// weigh the distribution by the rate and assign it to the CTMC
				Distribution meanDistr = new Distribution(meanDistrs.get(entrance));
				Set<Integer> distrSupport = meanDistrs.get(entrance).getSupport();
				for ( int s : distrSupport) {
					meanDistr.set(s, meanDistr.get(s) * meanRateWithinPotato);
				}
				ctmc.trans.set(entrance, meanDistr);
			}
		}
		
		// Then, reduce the CTMC to a DTMC.
		DTMCSimple dtmc = ctmc.buildUniformisedDTMC(uniformizationRate);
		return dtmc;
	}
	
	/**
	 * Uses {@code actmcRew} and current {@code pdMap} to construct
	 * equivalent {@code mcRewards} for uniformized {@code dtmc} (created by {@code constructUniformizedDTMC()}.
	 * The rewards are also uniformized iff {@code computingSteadyState} is set to false.
	 * @param dtmc 
	 * @param computingSteadyState If set to true, the rewards will be constructed for steady-state rewards
	 *  						   (mean payoff). Otherwise they are constructed for reachability rewards.
	 *  						   This should be true for steady-state rewards type of computations,
	 *  						   and false for reachability rewards.
	 * @return {@code MCRewards} equivalent to actmcRew.
	 */
	private MCRewards constructDTMCRew(DTMCSimple dtmc, boolean computingSteadyState) throws PrismException {
		if (computingSteadyState) {
			return constructUnaffectedDTMCRew(dtmc);
		} else {
			return constructUniformizedDTMCRew(dtmc);
		}
	}
	
	/**
	 * Uses {@code actmcRew} and current {@code pdMap} to construct equivalent
	 * {@code mcRewards} for uniformized {@code dtmc} (created by {@code constructUniformizedDTMC()}.
	 * The rewards are not adjusted for the uniformization rate.
	 * However, the effect of non-exponential transition rewards is still factored in.
	 * @param dtmc 
	 * @return Non-uniformized {@code MCRewards} equivalent to actmcRew.
	 */
	private StateRewardsSimple constructUnaffectedDTMCRew(DTMCSimple dtmc) throws PrismException {
		StateRewardsSimple newRew = new StateRewardsSimple();
		if (actmcRew == null) {
			return newRew;
		}
		
		int numStates = actmc.getNumStates();
		double[] rewArray = new double[actmc.getNumStates()];
		for (int s = 0; s < numStates ; ++s) {
			rewArray[s] = actmcRew.getStateReward(s);
		}
		
		for (Map.Entry<String, ACTMCPotato> pdEntry : pdMap.entrySet()) {
			ACTMCPotato potatoData = pdEntry.getValue();
			Map<Integer, Distribution> meanTimes = potatoData.getMeanTimes();
			Set<Integer> entrances = potatoData.getEntrances();
			
			double[] tmp = new double[actmc.getNumStates()];
			potatoData.applyEventRewards(tmp, true);
			// the obtained values must be divided by the mean time it takes to leave the potato
			for (int entrance : entrances) {
				double theta = meanTimes.get(entrance).sum();
				tmp[entrance] = tmp[entrance] / theta;
				rewArray[entrance] += tmp[entrance];
			}	
		}
		
		for (int s = 0; s < numStates ; ++s) {
			newRew.setStateReward(s, rewArray[s]);
		}
		
		return newRew;
	}
	
	/**
	 * Uses {@code actmcRew} and current {@code pdMap} to construct equivalent 
	 * {@code mcRewards} for uniformized {@code dtmc} (created by {@code constructUniformizedDTMC()}.
	 * The rewards are uniformized.
	 * @param dtmc 
	 * @return Uniformized {@code MCRewards} equivalent to actmcRew.
	 */
	private StateRewardsSimple constructUniformizedDTMCRew(DTMCSimple dtmc) throws PrismException {
		StateRewardsSimple newRew = new StateRewardsSimple();
		if (actmcRew == null) {
			return newRew;
		}
		
		int numStates = dtmc.getNumStates();
		for (int s = 0; s < numStates ; ++s) {
			double rew = actmcRew.getStateReward(s);
			if (rew > 0) {
				newRew.setStateReward(s, rew / dtmc.uniformizationRate);
			}
		}
		
		for (Map.Entry<String, ACTMCPotato> pdEntry : pdMap.entrySet()) {
			ACTMCPotato potatoData = pdEntry.getValue();
			Set<Integer> entrances = potatoData.getEntrances();
			for (int entrance : entrances) {
				double rew = potatoData.getMeanRewards().get(entrance);
				if (rew > 0) {
					double theta = potatoData.getMeanTimes().get(entrance).sum();
					double meanRew = rew / theta;//average reward over average time spent within
					newRew.setStateReward(entrance, meanRew / dtmc.uniformizationRate);
				}
			}
		}

		return newRew;
	}

	/**
	 * Creates a map where the keys are string identifiers of the GSMPEvents,
	 * and the values are corresponding ACTMCPotato structures.
	 * This is useful as to enable reusage of the ACTMCPotato structures efficiently.
	 * @param actmc ACTMC model for which to create the ACTMCPotato structures
	 * @param rew Optional rewards associated with {@code actmc}. May be null, but calls
	 *            to {@code ACTMCPotato.getMeanReward()} will throw an exception!
	 */
	private Map<String, ACTMCPotato> createPotatoDataMap(ACTMCSimple actmc,
			ACTMCRewardsSimple rew, BitSet target) throws PrismException {
		Map<String, ACTMCPotato> pdMap = new HashMap<String, ACTMCPotato>();
		List<GSMPEvent> events = actmc.getEventList();
		
		for (GSMPEvent event: events) {
			ACTMCPotato potatoData;
			
			switch (event.getDistributionType().getEnum()) {
			case DIRAC:
				potatoData = new ACTMCPotatoDirac(actmc, event, rew, target);
				break;
			case ERLANG:
				potatoData = new ACTMCPotatoErlang(actmc, event, rew, target);
				break;
			case EXPONENTIAL:
				potatoData = new ACTMCPotatoExponential(actmc, event, rew, target);
				break;
			case UNIFORM:
				potatoData = new ACTMCPotatoUniform(actmc, event, rew, target);
				break;
			case WEIBULL:
				throw new UnsupportedOperationException("ACTMCReduction does not yet support the Weibull distribution!");
				// TODO MAJO - implement weibull distributed event support
				//break;
			default:
				throw new PrismException("ACTMCReduction received an event with unrecognized distribution!");
			}
			
			pdMap.put(event.getIdentifier(), potatoData);
		}
		return pdMap;
	}
	
}