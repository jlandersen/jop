package com.jopdesign.wcet.analysis;

import com.jopdesign.build.MethodInfo;
import com.jopdesign.common.code.ControlFlowGraph;
import com.jopdesign.wcet.Project;
import com.jopdesign.wcet.ipet.CostProvider;
import com.jopdesign.wcet.ipet.IPETBuilder;
import com.jopdesign.wcet.ipet.IPETConfig;
import com.jopdesign.wcet.ipet.IPETSolver;
import com.jopdesign.wcet.ipet.IPETUtils;

import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;

/**
 * Class for recursive maximization problems.
 * Generalizes some concepts also useful outside the actual WCET analysis.
 *
 * @author Benedikt Huber <benedikt.huber@gmail.com>
 *
 * @param <Context> Different Contexts may lead to different results.
 *                  Recomputation with the same context is cached.
 * @param <Rval>    Type of the thing being computed (e.g., WcetCost, long) etc.
 */
public abstract class RecursiveAnalysis<Context extends AnalysisContext, Rval> {

	/** Used for configuring recursive WCET caluclation */
	public interface RecursiveStrategy<Context extends AnalysisContext, Rval> {

		public Rval recursiveCost(RecursiveAnalysis<Context,Rval> stagedAnalysis,
				ControlFlowGraph.InvokeNode invocation,
				Context ctx);
	}
	
	/** Key for caching recursive calculations */
	protected class CacheKey {
		MethodInfo m;
		Context ctx;
		public CacheKey(MethodInfo m, Context mode) {
			this.m = m; this.ctx = mode;
		}

		public int hashCode() {
			final int prime = 31;
			int result = prime * m.getFQMethodName().hashCode();
			result = prime * result + ctx.hashCode();
			return result;
		}

		@SuppressWarnings("unchecked")
		@Override
		public boolean equals(Object obj) {
			if (this == obj) return true;
			if (obj == null) return false;
			if (getClass() != obj.getClass()) return false;
			RecursiveAnalysis.CacheKey other = (RecursiveAnalysis.CacheKey) obj;
			if (!ctx.equals(other.ctx)) return false;
			if (!m.equals(other.m)) return false;
			return true;
		}

		@Override
		public String toString() {
			return this.m.getFQMethodName()+"["+this.ctx.hashCode()+"]";
		}
	}

	private Project project;
	private Hashtable<CacheKey, Rval> costMap;
	private IPETConfig ipetConfig;

	public Project getProject() {
		return project;
	}

	public RecursiveAnalysis(Project p, IPETConfig ipetConfig) {
		this.project = p;
		this.ipetConfig = ipetConfig;
		this.costMap = new Hashtable<CacheKey,Rval>();
	}

	public Rval computeCost(MethodInfo m, Context ctx) {
		/* use memoization to speed up analysis */
		CacheKey key = new CacheKey(m,ctx);
		if(isCached(key)) return getCached(key);

		/* compute solution */
		Rval rval = computeCostUncached(key.toString(), project.getFlowGraph(m), ctx);
		recordCost(key, rval);
		return rval;
	}

	public Rval computeCostUncached(String ilpName, ControlFlowGraph cfg, Context ctx) {
		Map<ControlFlowGraph.CFGNode, Rval> nodeCosts = buildNodeCostMap(cfg,ctx);
		CostProvider<ControlFlowGraph.CFGNode> costProvider = getCostProvider(nodeCosts);

		Map<IPETBuilder.ExecutionEdge, Long> edgeFlowOut = new HashMap<IPETBuilder.ExecutionEdge, Long>();
		long maxCost = runLocalComputation(ilpName, cfg, ctx, costProvider, edgeFlowOut);
		return extractSolution(cfg, nodeCosts, maxCost, edgeFlowOut);
	}

	protected abstract Rval extractSolution(
			ControlFlowGraph cfg,
			Map<ControlFlowGraph.CFGNode, Rval> nodeCosts,
			long maxCost,
			Map<IPETBuilder.ExecutionEdge, Long> edgeFlow);

	protected abstract CostProvider<ControlFlowGraph.CFGNode> getCostProvider(Map<ControlFlowGraph.CFGNode, Rval> nodeCosts);

	protected abstract Rval computeCostOfNode(ControlFlowGraph.CFGNode n, Context ctx);

	/**
	 * Compute the cost of the given control flow graph, using a local ILP
	 * @param name name for the ILP problem
	 * @param cfg the control flow graph
	 * @param ctx the context to use
	 * @return the cost for the given CFG
	 */
	public long runLocalComputation(
			String name, 
			ControlFlowGraph cfg, 
			Context ctx,
			CostProvider<ControlFlowGraph.CFGNode> costProvider,
			Map<IPETBuilder.ExecutionEdge, Long> edgeFlowOut) {
		
		IPETSolver problem = IPETUtils.buildLocalILPModel(name, ctx.getCallString(), cfg, costProvider, ipetConfig);
		/* solve ILP */
		/* extract node flow, local cost, cache cost, cummulative cost */
		long maxCost = 0;
		try {
			maxCost = Math.round(problem.solve(edgeFlowOut));
		} catch (Exception e) {
			throw new Error("Failed to solve LP problem: "+e,e);
		}
		return maxCost;
	}

	/**
	 * map flowgraph nodes to costs
	 * If the node is a invoke, we need to compute the cost for the invoked method
	 * otherwise, just take the basic block cost
	 * @param cfg the target flowgraph
	 * @param context the cost computation context
	 * @return
	 */
	public Map<ControlFlowGraph.CFGNode, Rval>
		buildNodeCostMap(ControlFlowGraph fg,Context ctx) {

		HashMap<ControlFlowGraph.CFGNode, Rval> nodeCost = new HashMap<ControlFlowGraph.CFGNode,Rval>();
		for(ControlFlowGraph.CFGNode n : fg.getGraph().vertexSet()) {
			nodeCost.put(n, computeCostOfNode(n, ctx));
		}
		return nodeCost;
	}

	public void recordCost(MethodInfo invoked, Context ctx, Rval cost) {
		recordCost(new CacheKey(invoked,ctx),cost);
	}
	protected void recordCost(CacheKey key, Rval cost) {
		costMap.put(key, cost);
	}
	protected boolean isCached(CacheKey key) {
		return costMap.containsKey(key);
	}
	public boolean isCached(MethodInfo invoked, Context ctx) {
		return isCached(new CacheKey(invoked,ctx));
	}
	protected Rval getCached(CacheKey cacheKey) {
		return costMap.get(cacheKey);
	}
	public Rval getCached(MethodInfo invoked, Context ctx) {
		return getCached(new CacheKey(invoked,ctx));
	}

}
