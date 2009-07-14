/*
  This file is part of JOP, the Java Optimized Processor
    see <http://www.jopdesign.com/>

  Copyright (C) 2009, Benedikt Huber (benedikt.huber@gmail.com)
  
  This program is free software: you can redistribute it and/or modify
  it under the terms of the GNU General Public License as published by
  the Free Software Foundation, either version 3 of the License, or
  (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package com.jopdesign.timing.jop;

import java.io.File;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.Vector;
import java.util.Map.Entry;

import com.jopdesign.timing.ConsoleTable;
import com.jopdesign.timing.InstructionInfo;
import com.jopdesign.timing.TimingTable;
import com.jopdesign.timing.WCETInstruction;
import com.jopdesign.timing.ConsoleTable.Alignment;
import com.jopdesign.timing.ConsoleTable.TableRow;
import com.jopdesign.timing.jop.MicrocodeAnalysis.MicrocodeVerificationException;
import com.jopdesign.tools.JopInstr;

/** Microcode Timing Table 
 * Before generating the timing table do not forget to run e.g.
 * {@code make gen_mem -e ASM_SRC=jvm JVM_TYPE=USB @} 
 */
public class JOPTimingTable extends TimingTable<JOPInstructionInfo> {


	/**
	 * Create a new timing table, reading the generated assembler file.
	 * @param file the assembler file
	 * @return the calculated timing table
	 */
	public static JOPTimingTable getTimingTable(File file)  {
		MicropathTable mpt = MicropathTable.getTimingTable(file);
		JOPTimingTable tt = new JOPTimingTable(mpt);
		return tt;
	}

	private MicropathTable micropathTable;

	@Override
	public long getCycles(JOPInstructionInfo instrInfo) {
		return getCycles(instrInfo.getOpcode(), instrInfo.hit, instrInfo.wordsLoaded);
	}
	
	public long getLocalCycles(int opcode) {
		return getCycles(opcode, false, 0);
	}

	public long getCycles(int opcode, boolean isHit, int words) {
		Vector<MicropathTiming> timing = this.getTiming(opcode);
		if(words < 0) {
			if(hasBytecodeLoad(timing)) {
				throw new AssertionError("Cannot calculate WCET of instruction accessing method cache"+
                "without information on the size of the method");
			}
		}
		return this.getCycles(timing, this.methodCacheAccessCycles(isHit, words));
	}

	public boolean hasTimingInfo(int opcode) {
		return this.timingTable.containsKey(opcode);
	}
	
	/**
	 * Method load time on invoke or return if there is a cache miss.
	 * 
	 * @see ms thesis p 232
	 */
	public long methodCacheAccessCycles(boolean hit, int words) {
		int b = -1;
		int c = readCycles > 0 ? (readCycles-1) : 0;
		if (hit) {
			b = 4;
		} else {
			b = 6 + (words+1) * (2+c);
		}
		return b;
	}

	public long methodCacheHiddenAccessCycles(boolean onInvoke) {
		return (onInvoke ? minCyclesHiddenOnInvoke : minCyclesHiddenOnReturn);
	}

	public long methodCacheHiddenAccessCycles(int opcode) {
		long hiddenAccess = Long.MAX_VALUE;
		for(MicropathTiming path : this.timingTable.get(opcode)) {
			if(! path.hasBytecodeLoad()) continue;
			hiddenAccess = Math.min(hiddenAccess,path.getHiddenBytecodeLoadCycles());
		}
		return hiddenAccess;
	}

	public long javaImplBcDispatchCycles(int opcode, boolean isHit, int mWords) {
		if(this.hasTimingInfo(opcode)) {
			if(! hasBytecodeLoad(this.timingTable.get(opcode))) {
				throw new AssertionError(""+ JopInstr.OPCODE_NAMES[opcode]+ 
						                 " is not a java implemented bytecode");
			}
			return this.getCycles(opcode, isHit, mWords);
		} else {
			return this.getCycles(MicrocodeAnalysis.JOPSYS_NOIM, isHit, mWords);
		}
	}

	protected JOPTimingTable(MicropathTable mpt) {
		this.micropathTable = mpt;
		timingTable = new TreeMap<Integer,Vector<MicropathTiming>>();
		analysisErrors = new TreeMap<Integer,MicrocodeVerificationException>(mpt.getAnalysisErrors());
		this.readCycles = -1; // not configured
		for(int i = 0; i < 256; i++) {
			if(mpt.hasTiming(i)) {
				try {
					this.timingTable.put(i, calculateTiming(i));
				} catch(MicrocodeVerificationException ex) {
					this.analysisErrors.put(i, ex);
				}
			}
		}
		calculateHiddenCycles();
	}
	
	private void calculateHiddenCycles() {
		Set<Integer> returnOpcodes = new HashSet<Integer>();
		long rhidden = Integer.MAX_VALUE;
		for(int rop : InstructionInfo.RETURN_OPCODES) {
			rhidden = Math.min(rhidden, this.methodCacheHiddenAccessCycles(rop));
			returnOpcodes.add(rop);
		}
		this.minCyclesHiddenOnReturn = rhidden;
		
		/* now check all other opcodes with a bytecode load */
		long ihidden = Integer.MAX_VALUE;
		for(int iop = 0; iop < 256; iop++) {
			if(returnOpcodes.contains(iop)) continue;
			if(! hasTimingInfo(iop)) continue;
			ihidden = Math.min(ihidden, methodCacheHiddenAccessCycles(iop));
		}
		this.minCyclesHiddenOnInvoke = ihidden;
	}
	
	private Vector<MicropathTiming> calculateTiming(int opcode) throws MicrocodeVerificationException {
		Vector<MicropathTiming> timings = new Vector<MicropathTiming>();
		for(MicrocodePath p : micropathTable.getMicroPaths(opcode)) {
			MicropathTiming mt = new MicropathTiming(p);
			timings.add(mt);
		}
		return timings;
	}


	private int readCycles;
	private int writeCycles;
	
	
	private TreeMap<Integer, MicrocodeVerificationException> analysisErrors;	
	private TreeMap<Integer, Vector<MicropathTiming>> timingTable;
	protected long minCyclesHiddenOnInvoke = 0;
	protected long minCyclesHiddenOnReturn = 0;
	
	public MicrocodeVerificationException getAnalysisError(int opcode) { 
		return analysisErrors.get(opcode); 
	}
	public boolean isImplemented(int opcode) {
		return micropathTable.isImplemented(opcode);
	}
	
	
	public void configureWaitStates(int r, int w) {
		this.readCycles = r;
		this.writeCycles = w;
	}
	
	public Vector<MicropathTiming> getTiming(int opcode) {
		if(analysisErrors.containsKey(opcode)) {
			throw new AssertionError("Failed to analyse microcode timing: "+opcode);			
		}
		if(! micropathTable.hasMicrocodeImpl(opcode)) {
			return this.timingTable.get(MicrocodeAnalysis.JOPSYS_NOIM);
		} else {
			return this.timingTable.get(opcode);			
		}
	}
		
	private long getCycles(Vector<MicropathTiming> timing, long bytecodeDelay) {
		long maxCycles = 0;
		for(MicropathTiming mtiming : timing) {
			maxCycles = Math.max(maxCycles, mtiming.getCycles(readCycles, writeCycles, bytecodeDelay));
		}
		return maxCycles;
	}

	public boolean hasBytecodeLoad(short opcode) {
		return hasBytecodeLoad(this.getTiming(opcode));
	}

	protected static boolean hasBytecodeLoad(Vector<MicropathTiming> timings) {
		for(MicropathTiming ptime : timings) {
			if(ptime.hasBytecodeLoad()) return true;
		}
		return false;
	}

	/**
	 * Print the microcode timing table from jvmgen.asm
	 * @param argv
	 */
	public static void main(String argv[]) {
		String head = "JOP Timing Table on " + new Date();
		System.out.println(head);
		System.out.println(ConsoleTable.getSepLine('=',head.length()));
		System.out.println();

		
		System.out.println("  Loading " + MicrocodeAnalysis.DEFAULT_ASM_FILE);
		System.out.println("    Before generating the timing table do not forget to run e.g.");
		System.out.println("    > make gen_mem -e ASM_SRC=jvm JVM_TYPE=USB\n"); 

		JOPTimingTable tt = null;
		try {
			tt = JOPTimingTable.getTimingTable(MicrocodeAnalysis.DEFAULT_ASM_FILE);
		} catch (Exception e) {
			e.printStackTrace();
			System.exit(1);
		}
		
		// build table
		ConsoleTable table = dumpTimingTable(tt);

		System.out.println(table.render());

		/* for now, check if we agree with WCETInstruction */
		checkWithWCETInstruction(tt);
	}
	
	private static ConsoleTable dumpTimingTable(JOPTimingTable tt) 
	{
		ConsoleTable table = new ConsoleTable();
		table.addColumn("opcode", Alignment.ALIGN_RIGHT)
		.addColumn("name", Alignment.ALIGN_LEFT)
		.addColumn("timing path", Alignment.ALIGN_LEFT)
		.addColumn("(1,2,H)",Alignment.ALIGN_RIGHT)
		.addColumn("(1,2,32)",Alignment.ALIGN_RIGHT)
		.addColumn("(3,5,H)",Alignment.ALIGN_RIGHT)
		.addColumn("(3,5,32)",Alignment.ALIGN_RIGHT);
		for(int i = 0; i < 256; i++) {
			int opcode = i;
			if(JopInstr.isReserved(opcode)) continue;
			TableRow row = table.addRow();
			row.addCell(opcode)
			.addCell(JopInstr.OPCODE_NAMES[i]);
			if(tt.getAnalysisError(i) != null) {
				row.addCell("... FAILED: "+tt.getAnalysisError(i).getMessage()+" ...",5,Alignment.ALIGN_LEFT);
			} else if (! tt.isImplemented(i)) {
				row.addCell("... no microcode implementation ...",5,Alignment.ALIGN_LEFT);
			} else {
				String timingPath = MicropathTiming.toString(tt.getTiming(opcode));
				tt.configureWaitStates(1, 2);
				long exampleTiming1 = tt.getCycles(opcode, true, 0);
				long exampleTiming2 = tt.getCycles(opcode, false, 32);
				tt.configureWaitStates(3, 5);
				long exampleTiming3 = tt.getCycles(opcode, true, 0);
				long exampleTiming4 = tt.getCycles(opcode, false, 32);
				row.addCell(timingPath)
				.addCell(exampleTiming1)
				.addCell(exampleTiming2)
				.addCell(exampleTiming3)
				.addCell(exampleTiming4);
			}
		}
		table.addLegendTop("  (x,y,z) ~ (read delay, write delay, bytecode access)");
		table.addLegendTop("  z = H ... cache hit, n ... load n words into cache");
		table.addLegendTop("  infeasible branches: "+Arrays.toString(MicrocodeAnalysis.INFEASIBLE_BRANCHES));
		table.addLegendBottom("  [expr] denotes max(0,expr)");
		table.addLegendBottom("  r = read delay, w = write delay, b = bytecode load delay");
		table.addLegendBottom(String.format("  hidden cycles on invoke (including JavaImplBCs) and return: %d / %d",
				tt.minCyclesHiddenOnInvoke,tt.minCyclesHiddenOnReturn));
		return table;
	}
	private static void checkWithWCETInstruction(JOPTimingTable tt) {
		// demo config
		tt.configureWaitStates(1, 2);
		// test set
		boolean[] testHit  = { true, true, false, false, false };
		int[]     testLoad = {    1,  128,     1,    11,    57 };
		
		Map<Integer,String> failures = new TreeMap<Integer,String>();
		tt.configureWaitStates(WCETInstruction.r, WCETInstruction.w);
		outer:
		for(int i = 0; i < 256; i++) {
			long wiTime0 = WCETInstruction.getCycles(i, false, 0);
			if(JopInstr.isReserved(i)) {
				if(wiTime0 >= 0) {
					failures.put(i,"WCETInstruction has timing: "+wiTime0+", but JopInstr says this is a RESERVED INSTRUCTION");
				} 
				continue;				
			}
			if(! tt.isImplemented(i)) {
				if(wiTime0 >= 0) {
					failures.put(i,"WCETInstruction has timing: "+wiTime0+", but there is NO MICROCODE IMPLEMENTATION");
				} 
				continue;
			}
			if(tt.getAnalysisError(i) != null) {
				if(wiTime0 >= 0) {
					failures.put(i, "WCETInstruction has timing: "+wiTime0+", but we FAILED TO ANALYSE the microcode");
				} 
				continue;				
			}
			String tString = MicropathTiming.toString(tt.getTiming(i));
			if(wiTime0 < 0) {
				failures.put(i,"WCETInstruction HAS NO TIMING information, but we have: "+tString);
				continue;
			}
			for(int j = 0; j < testHit.length; j++) {
				long wiT = WCETInstruction.getCycles(i, ! testHit[j], testLoad[j]);
				long wiMA = tt.getCycles(i, testHit[j],testLoad[j]);
				if(wiT != wiMA) {
					failures.put(i,"WCETInstruction has DIFFERENT TIMING INFO "+
							       "(hit = " + testHit[j] + ", load = " + testLoad[j] + "): " +
							     "microcodeanalysis := " + wiMA + " /= " + wiT + " =: wcetinstruction"); 
					continue outer;
				}
			}
		}
		for(Entry<Integer, String> fail : failures.entrySet()) {
			int opc = fail.getKey();
			System.err.println("["+opc+"] " + JopInstr.OPCODE_NAMES[opc]+" : "+fail.getValue());
		}
	}

}