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

package parser.type;

import parser.Values;
import parser.ast.Expression;
import prism.PrismLangException;

public class TypeDistributionUniform extends TypeDistribution {

	private static TypeDistributionUniform singleton;
	
	static
	{
		singleton = new TypeDistributionUniform();
	}
	
	private TypeDistributionUniform()
	{		
	}	
	
	@Override
	public String getTypeString()
	{
		return "Uniform distribution";
	}
	
	@Override
	public Object defaultValue()
	{
		throw new UnsupportedOperationException("not yet implemented");
	}
	
	public static TypeDistributionUniform getInstance()
	{
		return singleton;
	}
	
	@Override
	public int canAssign(Type firstType, Type secondType)
	{
		if (!(firstType instanceof TypeDouble || firstType instanceof TypeInt)) {
			return 1;
		}
		if (!(secondType instanceof TypeDouble || secondType instanceof TypeInt)) {
			return 2;
		}
		return 0;
	}
	
	@Override
	public boolean parameterValueCheck(Expression firstParameter, Expression secondParameter, Values constantValues) throws PrismLangException{
		if ((double)firstParameter.evaluateDouble(constantValues) < 0) {
			throw new PrismLangException(getTypeString() + " must have two parameters of values 0<=a<b", firstParameter);
		}
		if ((double)secondParameter.evaluateDouble(constantValues) <= 0) {
			throw new PrismLangException(getTypeString() + " must have two parameters of values 0<=a<b", secondParameter);
		}
		if ((double)firstParameter.evaluateDouble(constantValues) >= (double)secondParameter.evaluateDouble(constantValues)) {
			throw new PrismLangException(getTypeString() + " must have two parameters of values 0<=a<b", firstParameter);
		}
		return true;
	}
	
	@Override
	public int getNumParams() {
		return 2;
	}

}