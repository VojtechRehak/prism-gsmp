//==============================================================================
//	
//	Copyright (c) 2017-
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

package parser.visitor;

import parser.Values;
import parser.ast.*;
import parser.type.*;
import prism.PrismLangException;

/**
 * Check for value-correctness.
 */
public class ValueCheck extends ASTTraverse
{
	
	private Values evaluatedConstants = null;

	public ValueCheck(Values evaluatedConstants)
	{
		this.evaluatedConstants = evaluatedConstants;
	}
	
	public void visitPost(SynthParam e) throws PrismLangException
	{
		// first, evaluate the expressions and store them for later convenience
		e.setEventName(e.getEventExpr().getName());
		e.setParamIndex(e.getParamIndexExpr().evaluateInt(evaluatedConstants));
		e.setLowerBound(e.getLowerBoundExpr().evaluateDouble(evaluatedConstants));
		e.setUpperBound(e.getUpperBoundExpr().evaluateDouble(evaluatedConstants));
		
		// now, perform value checking
		ModulesFile mf = e.getParent().getParent().getModulesFile();
		String distributionName = mf.getEvent(e.getEventName()).getDistributionName();
		int distributionIndex = mf.getDistributionList().getDistributionIndex(distributionName);
		TypeDistribution distributionType = mf.getDistributionList().getDistributionType(distributionIndex);
		Expression firstDefParam = mf.getDistributionList().getFirstParameter(distributionIndex);
		Expression secondDefParam = mf.getDistributionList().getSecondParameter(distributionIndex);
		if (distributionType.getNumParams() < e.getParamIndex()) {
			throw new PrismLangException("Synthesis parameter index is " + e.getParamIndex() + 
					", but " + distributionType.getTypeString() + 
					" only has " + distributionType.getNumParams() + 
					" parameters!",
					e.getParamIndexExpr());
		}
		if (e.getParamIndex() <= 0) {
			throw new PrismLangException("Parameter index must be greater than zero!", e.getParamIndexExpr());
		}
		if (e.getParamIndex() == 1) {
			try {
				distributionType.parameterValueCheck(e.getLowerBoundExpr(), secondDefParam, evaluatedConstants);
			} catch (Exception exception) {
			     throw new PrismLangException("Invalid synthesis parameter lower bound for event" + e.getEventName(), e.getLowerBoundExpr());
			}
			try {
				distributionType.parameterValueCheck(e.getUpperBoundExpr(), secondDefParam, evaluatedConstants);
			} catch (Exception exception) {
			     throw new PrismLangException("Invalid synthesis parameter upper bound for event" + e.getEventName(), e.getUpperBoundExpr());
			}
		}
		if (e.getParamIndex() == 2) {
			try {
				distributionType.parameterValueCheck(firstDefParam, e.getLowerBoundExpr(), evaluatedConstants);
			} catch (Exception exception) {
			     throw new PrismLangException("Invalid synthesis parameter lower bound for event"  + e.getEventName(), e.getLowerBoundExpr());
			}
			try {
				distributionType.parameterValueCheck(firstDefParam, e.getUpperBoundExpr(), evaluatedConstants);
			} catch (Exception exception) {
			     throw new PrismLangException("Invalid synthesis parameter upper bound for event"  + e.getEventName(), e.getUpperBoundExpr());
			}
		}
		
		if (e.getLowerBound() >= e.getUpperBound()) {
			throw new PrismLangException("Upper bound must be greater than the lower bound!", e.getUpperBoundExpr());
}
	}
	
	public void visitPost(DistributionList e) throws PrismLangException // TODO MAJO - currently unused, and rather done later at build time.
	{
		int i, n;
		n = e.size();
		for (i = 0; i < n ; ++i) {
			TypeDistribution dType = e.getDistributionType(i);
			dType.parameterValueCheck(e.getFirstParameter(i), e.getSecondParameter(i), evaluatedConstants); 	
		}
	}
}
