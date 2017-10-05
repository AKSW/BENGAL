/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.aksw.simba.bengal.selector;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;

/**
 *
 * @author ngonga
 */
public class SimpleSummarySelector extends AbstractSelector {

	private Set<String> sourceClasses;
	private List<Resource> resources;
	private Random r = new Random(20);
	@SuppressWarnings("unused")
	private int minSize = 1;
	private int maxSize = 5;

	/**
	 * Constructor
	 * 
	 * @param sourceClasses
	 *            Classes for subjects
	 * @param targetClasses
	 *            Classes for objects
	 * @param endpoint
	 *            SPARQL endpoint
	 * @param graph
	 *            Graph to query (null if none)
	 * @param minSize
	 *            Minimal size of summary
	 * @param maxSize
	 *            Maximal size of summary
	 */
	public SimpleSummarySelector(Set<String> sourceClasses, Set<String> targetClasses, String endpoint, String graph,
			int minSize, int maxSize, long seed, boolean useSymmetricCbd) {
		super(targetClasses, endpoint, graph, useSymmetricCbd);
		this.sourceClasses = sourceClasses;
		resources = null;
		this.minSize = minSize;
		if (maxSize < minSize) {
			maxSize = minSize + 1;
		}
		this.maxSize = maxSize;
		this.r = new Random(seed);
	}

	/**
	 * Constructor
	 * 
	 * @param sourceClasses
	 *            Classes for subjects
	 * @param targetClasses
	 *            Classes for objects
	 * @param endpoint
	 *            SPARQL endpoint
	 * @param graph
	 *            Graph to query (null if none)
	 */
	public SimpleSummarySelector(Set<String> sourceClasses, Set<String> targetClasses, String endpoint, String graph) {
		super(targetClasses, endpoint, graph);
		this.sourceClasses = sourceClasses;
		resources = null;
	}

	/**
	 * Returns the next set of statements generated by this selector
	 * 
	 * @return Set of statements
	 */
	@Override
	public List<Statement> getNextStatements() {
		if (resources == null) {
			resources = getResources(sourceClasses);
		}
		int counter = Math.abs(r.nextInt() % resources.size());
		// get symmetric CBD
		List<Statement> statements = getSummary(resources.get(counter));
		if (statements == null) {
			// there was an error
			return null;
		}

		// now pick random statements
		Set<Statement> result = new HashSet<>();
		// int size = minSize + r.nextInt(maxSize - minSize + 1);
		int size = maxSize;
		// check for size, if size > statements simply take statements
		if (size >= statements.size())
			return sortStatements(new HashSet<Statement>(statements));
		while (result.size() < size) {
			counter = Math.abs(r.nextInt() % statements.size());
			result.add(statements.get(counter));
		}
		// System.out.println(result);
		return sortStatements(result);
	}

	public static void main(String args[]) {
		Set<String> classes = new HashSet<>();
		classes.add("<http://dbpedia.org/ontology/Person>");
		classes.add("<http://dbpedia.org/ontology/Place>");
		classes.add("<http://dbpedia.org/ontology/Organisation>");
		SimpleSummarySelector sss = new SimpleSummarySelector(classes, classes, "http://dbpedia.org/sparql", null);
		sss.getNextStatements();
		sss.getNextStatements();
	}
}
