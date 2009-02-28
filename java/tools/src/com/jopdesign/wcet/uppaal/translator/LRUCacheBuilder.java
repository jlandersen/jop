package com.jopdesign.wcet.uppaal.translator;

import com.jopdesign.wcet.jop.BlockCache;
import com.jopdesign.wcet.uppaal.model.NTASystem;

public class LRUCacheBuilder extends CacheSimBuilder {
	private BlockCache cache;
	public LRUCacheBuilder(BlockCache blockCache) {
		this.cache = blockCache;
		// logger.info("LRU cache simulation with "+numBlocks+ " blocks");
	}
	@Override
	public void appendDeclarations(NTASystem system,String NUM_METHODS) {
		appendDeclsN(system,NUM_METHODS);
	}
	public void appendDeclsN(NTASystem system,String NUM_METHODS) {
		super.appendDeclarations(system,NUM_METHODS);
		system.appendDeclaration(String.format("int[0,%s] cache[%d] = %s;",
				NUM_METHODS,cache.getNumBlocks(),initCache(NUM_METHODS,cache.getNumBlocks())));
		system.appendDeclaration(String.format("bool lastHit;"));
		system.appendDeclaration(
				"void access_cache(int mid) {\n" +
				"  lastHit = false;\n"+
				"  if(cache[0] == mid) {\n"+
				"    lastHit = true;\n"+
				"  } else {\n"+
				"    int i = 0;\n"+
				"    int last = cache[0];\n"+
				"    for(i = 0; i < "+(cache.getNumBlocks()-1)+" && (! lastHit); i++) {\n"+
				"      int next = cache[i+1];\n"+
				"      if(next == mid) {\n"+
				"        lastHit = true;\n"+
				"      }\n"+ 
				"      cache[i+1] = last;\n"+
				"      last = next;\n"+
				"    }\n"+
				"    cache[0] = mid;\n"+
				"  }\n"+
				"}\n");
	}
	private String initCache(String NUM_METHODS,int numBlocks) {
		StringBuffer sb = new StringBuffer("{ 0");
		for(int i = 1; i < numBlocks; i++) {
			sb.append(", ");
			sb.append(NUM_METHODS);
		}
		sb.append(" }");
		return sb.toString();
	}
}