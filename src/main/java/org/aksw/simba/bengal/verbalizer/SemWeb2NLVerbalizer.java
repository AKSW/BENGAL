/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.aksw.simba.bengal.verbalizer;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;

import org.aksw.gerbil.transfer.nif.Document;
import org.aksw.gerbil.transfer.nif.MeaningSpan;
import org.aksw.gerbil.transfer.nif.data.DocumentImpl;
import org.aksw.gerbil.transfer.nif.data.NamedEntity;
import org.aksw.simba.bengal.triple2nl.TripleConverter;
import org.aksw.simba.bengal.utils.DocumentHelper;
import org.apache.jena.graph.Triple;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.impl.PropertyImpl;
import org.apache.jena.rdf.model.impl.ResourceImpl;
import org.apache.jena.rdf.model.impl.StatementImpl;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.dllearner.kb.sparql.SparqlEndpoint;
import org.dllearner.kb.sparql.SparqlQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.carrotsearch.hppc.BitSet;

/**
 * A deterministic verbalizer which relies on the SemWeb2NL project.
 *
 * @author ngonga
 * @author roeder
 */
public class SemWeb2NLVerbalizer implements BVerbalizer, Comparator<NamedEntity> {

	private static final Logger LOGGER = LoggerFactory.getLogger(SemWeb2NLVerbalizer.class);

	private static final HashSet<String> BLACKLISTED_PROPERTIES = new HashSet<String>(
			Arrays.asList("http://www.w3.org/2000/01/rdf-schema#comment", "http://www.w3.org/2000/01/rdf-schema#label",
					"http://dbpedia.org/ontology/abstract", "http://dbpedia.org/ontology/wikiPageExternalLink",
					"http://dbpedia.org/class/yago/Wikicat", "http://commons.wikimedia.org/wiki",
					"http://www.w3.org/2002/07/owl#sameAs", "http://dbpedia.org/ontology/thumbnail"));

	private TripleConverter converter;
	private SparqlEndpoint endpoint;
	private boolean usePronouns;
	private boolean useSurfaceForms;

	public SemWeb2NLVerbalizer(SparqlEndpoint endpoint) {
		this(endpoint, false, false);
	}

	public SemWeb2NLVerbalizer(SparqlEndpoint endpoint, boolean usePronouns, boolean useSurfaceForms) {
		this.endpoint = endpoint;
		this.usePronouns = usePronouns;
		this.useSurfaceForms = useSurfaceForms;
		converter = new TripleConverter(this.endpoint);
	}

	@Override
	public Document generateDocument(List<Statement> triples) {
		// generate sub documents
		List<Document> subDocs = new ArrayList<Document>(triples.size());
		Document document;
		Triple t;
		Resource subject, oldSubject = null;
		int SFchange = 0;
		for (Statement s : triples) {
			subject = s.getSubject();
			if (!s.getObject().isAnon() && !BLACKLISTED_PROPERTIES.contains(s.getPredicate().getURI())) {

				t = Triple.create(subject.asNode(), s.getPredicate().asNode(), s.getObject().asNode());
				document = new DocumentImpl(converter.convert(t).toString());
				if (annotateDocument(document, s)) {
					// if the current subject has been seen in the sentence
					// before
					// we can replace it with a pronoun.
					if (usePronouns && (subject.equals(oldSubject) && SFchange == 1)) {
						replaceSubjectWithPronoun(document, subject.getURI());
					}
					// we can replace it with a list of surface forms.
					// if (useSurfaceForms && (subject.equals(oldSubject)) &&
					// SFchange == 0) {
					if (useSurfaceForms) {
						try {
							document = replaceSubjectWithSurfaceForms(document, s);
							SFchange = 1;
						} catch (IOException e) {
							// TODO Auto-generated catch block
							LOGGER.error("Couldn't replace Subject by SF. Aborting.", e);
						}
					}
					// document = reduceEntityDensity(document, s);
					subDocs.add(document);
					// System.out.println(document);
					oldSubject = subject;
				} else {
					LOGGER.info("One of the triples couldn't be translated. It will be ignored. triple={}", s);
				}
			}
		}

		// generate the document containing all the sub documents
		document = new DocumentImpl();
		StringBuilder textBuilder = new StringBuilder();
		for (Document subDoc : subDocs) {
			if (textBuilder.length() > 0) {
				textBuilder.append(' ');
			}
			// add the entities
			for (NamedEntity ne : subDoc.getMarkings(NamedEntity.class)) {
				ne.setStartPosition(ne.getStartPosition() + textBuilder.length());
				document.addMarking(ne);
			}
			// append the text
			textBuilder.append(subDoc.getText());
			textBuilder.append(' ');
		}
		document.setText(textBuilder.toString());
		document.addMarking(new NumberOfVerbalizedTriples(subDocs.size()));
		SFchange = 0;
		return document;
	}

	private boolean annotateDocument(Document document, Statement s) {
		if (s.getObject().isResource()) {
			return annotateDocument(document, s.getSubject(), s.getObject().asResource());
		} else {
			return annotateDocument(document, s.getSubject());
		}
	}

	/**
	 * Replaces the first occurrence of the statements subject with a pronoun.
	 * 
	 * @param document
	 * @param s
	 */

	public void replaceSubjectWithPronoun(Document document, String subjectUri) {
		// System.out.println("replacing by pronouns");
		MeaningSpan marking = DocumentHelper.searchFirstOccurrence(subjectUri, document);
		if (marking == null) {
			return;
		}

		String documentText = document.getText();
		String pronoun = null;

		int start = marking.getStartPosition();
		int length = marking.getLength();
		int end = start + length;
		// FIXME check whether the entity is preceded by an article

		// Check whether we have to add a possessive pronoun (check whether it
		// has a trailing "'s")
		boolean possessiveForm = false;
		if ((documentText.charAt(end - 2) == '\'') && (documentText.charAt(end - 1) == 's')) {
			possessiveForm = true;
		} else if (((end + 1) < documentText.length()) && (documentText.charAt(end) == '\'')) {
			possessiveForm = true;
			// Check whether we have "<entity>'" or "<entity>'s"
			if (documentText.charAt(end + 1) == 's') {
				length += 2;
			} else {
				length += 1;
			}
		}

		// Choose a pronoun based on the type of the entity
		String type = getType(subjectUri);
		if (type != null) {
			if (type.equals("http://dbpedia.org/ontology/Person")) {
				// Get the comment text
				String commentString = getGender(subjectUri);
				// Search for a pronoun that identifies the person as man
				if (commentString.contains(" he ") || commentString.contains("He ")
						|| commentString.contains(" his ")) {
					if (possessiveForm) {
						pronoun = (start == 0) ? "His" : "his";
					} else {
						pronoun = (start == 0) ? "He" : "he";
					}
					// Ok, than search for a woman
				} else if (commentString.contains(" she ") || commentString.contains("She ")
						|| commentString.contains(" her ")) {
					if (possessiveForm) {
						pronoun = (start == 0) ? "Her" : "her";
					} else {
						pronoun = (start == 0) ? "She" : "she";
					}
				}
				// If we can not decide the gender we shouldn't insert a pronoun
				// (let it be null)
			} else {
				if (possessiveForm) {
					pronoun = (start == 0) ? "Its" : "its";
				} else {
					pronoun = (start == 0) ? "It" : "it";
				}
			}
		}

		// If we couldn't find a pronoun
		if (pronoun == null) {
			return;
		}

		// Remove the marking from the document
		document.getMarkings().remove(marking);
		// Replace the text
		DocumentHelper.replaceText(document, start, length, pronoun);
	}

	// @SuppressWarnings("deprecation")
	private Document replaceSubjectWithSurfaceForms(Document document, Statement statements) throws IOException {
		// System.out.println("replacing by surface forms");
		MeaningSpan marking = DocumentHelper.searchFirstOccurrence(statements.getSubject().toString(), document);
		if (marking == null) {
			return document;
		}
		String oldText = document.getText();
		int start = marking.getStartPosition();
		int length = marking.getLength();
		int end = start + length;

		List<MeaningSpan> oldEntities = document.getMarkings(MeaningSpan.class);
		Map<MeaningSpan, String> entities = new HashMap<MeaningSpan, String>();

		for (MeaningSpan meaning : oldEntities) {
			entities.put(meaning,
					oldText.substring(meaning.getStartPosition(), meaning.getStartPosition() + meaning.getLength()));
		}

		String oldLabel = oldText.substring(start, end);
		String newText;
		Document newDoc = null;
		Map<String, String> labelsToUris = getLabelsForResources(statements);

		String labels[] = labelsToUris.keySet().toArray(new String[labelsToUris.size()]);
		Arrays.sort(labels);
		// Go through the array of labels (starting with the
		// longest) and search for them inside the paraphrased text.
		// Make sure that the entities are not overlapping.
		BitSet blockedPositions = new BitSet(document.getText().length());
		// BitSet currentPositions = new BitSet(document.getText().length());
		String label, uri;
		int pos;
		for (int i = 0; i < labels.length; ++i) {
			label = labels[i];
			if (label.equals("")) {
				return document;

			}
			uri = labelsToUris.get(label);
			newText = document.getText().replace(oldLabel, label);
			BitSet currentPositions = new BitSet(newText.length());
			newDoc = new DocumentImpl(newText, document.getDocumentURI());
			do {
				// search the position in the text (make sure that
				// we start behind the position we might have found
				// before)
				pos = newText.indexOf(label, start);
				if (pos >= 0) {
					currentPositions.clear();
					// check that this part of the String does not
					// already have been blocked
					currentPositions.set(pos, pos + label.length());
					// If this position is not intersecting with another
					// entity
					if (BitSet.intersectionCount(blockedPositions, currentPositions) == 0) {
						newDoc.addMarking(new NamedEntity(pos, label.length(), uri));
						blockedPositions.or(currentPositions);
						break;
					}
				}
			} while (pos >= 0);
		}
		int position = 0;
		Iterator<Entry<MeaningSpan, String>> it = entities.entrySet().iterator();
		while (it.hasNext()) {
			@SuppressWarnings("rawtypes")
			Map.Entry pair = (Map.Entry) it.next();
			MeaningSpan span = (MeaningSpan) pair.getKey();
			String value = pair.getValue().toString();
			position = newDoc.getText().indexOf(value);
			if (position != -1) {
				newDoc.addMarking(new NamedEntity(position, value.length(), span.getUri()));
			}
			it.remove(); // avoids a ConcurrentModificationException
		}
		// System.out.println(newDoc.toString());
		return newDoc;
	}

	private Map<String, String> getLabelsForResources(Statement statements) throws IOException {
		Map<String, String> labelsToUris = new HashMap<String, String>();
		String uri, label;
		uri = statements.getSubject().getURI();
		// System.out.println("Resource to get SF" + uri);
		label = getSurfaceForm(uri);
		// if(label.equals("")){
		// label = getEnglishLabel(uri);
		// }
		// System.out.println("Label SF" + label);
		if (label != null) {
			labelsToUris.put(label, uri);
		}
		return labelsToUris;
	}

	private String getSurfaceForm(String resource) throws IOException {
		try {
			Properties prop = new Properties();
			InputStream input = new FileInputStream("src/main/resources/config/bengal.properties");
			prop.load(input);

			String surfaceFormTSV = prop.getProperty("surfaceForms");
			// LOGGER.info("Getting surface forms from: " + surfaceFormTSV);
			File file = new File(surfaceFormTSV);
			String label = "";

			// System.out.println("Resource in getting SF" + resource);
			LOGGER.info("Start parsing: " + file);
			BufferedReader br = new BufferedReader(new FileReader(file));
			while (br.ready()) {
				String[] line = br.readLine().split("\t");
				try {
					String subject = line[0];
					String object = line[1];
					if (subject.equals(resource)) {
						label = object;
						break;
					}
				} catch (Exception e) {
				}
			}
			br.close();
			LOGGER.info("SurfaceFormChanger worked");
			return label;
		} catch (Exception e) {
			LOGGER.error("Got exception from SurfaceFormChanger. Using the original document.", e);
			return "";
		}

	}

	private String getType(String uri) {

		if (uri.equals(RDF.type.getURI())) {
			return "type";
		} else if (uri.equals(RDFS.label.getURI())) {
			return "label";
		}
		try {
			String labelQuery = "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>"
					+ " PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>"
					+ " PREFIX dbr: <http://dbpedia.org/resource/>" + " PREFIX dbo: <http://dbpedia.org/ontology/>"
					+ " PREFIX owl: <http://www.w3.org/2002/07/owl#>" + " SELECT  DISTINCT ?lcs WHERE {"
					+ " ?lcs ^rdf:type <" + uri + ">." + "?lcs rdfs:subClassOf ?x." + "?x rdfs:subClassOf owl:Thing."
					+ "?lcs rdfs:label []." + "}";

			// take care of graph issues. Only takes one graph. Seems like some
			// sparql endpoint do
			// not like the FROM option.
			ResultSet results = new SparqlQuery(labelQuery, endpoint).send();

			// get label from knowledge base
			String label = null;
			QuerySolution soln;
			while (results.hasNext()) {
				soln = results.nextSolution();
				// process query here
				{
					label = soln.getResource("lcs").toString();
				}
			}
			return label;
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}

	private String getGender(String uri) {

		if (uri.equals(RDF.type.getURI())) {
			return "type";
		} else if (uri.equals(RDFS.label.getURI())) {
			return "label";
		}
		try {
			String labelQuery = "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>"
					+ "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>"
					+ "PREFIX dbr: <http://dbpedia.org/resource/>" + "PREFIX dbo: <http://dbpedia.org/ontology/>"
					+ "PREFIX owl: <http://www.w3.org/2002/07/owl#>" + "SELECT  DISTINCT ?com WHERE {" + " <" + uri
					+ "> rdfs:comment ?com." + "FILTER (lang(?com) = 'en')." + "}";

			// take care of graph issues. Only takes one graph. Seems like some
			// sparql endpoint do
			// not like the FROM option.
			ResultSet results = new SparqlQuery(labelQuery, endpoint).send();

			// get label from knowledge base
			String label = null;
			QuerySolution soln;
			while (results.hasNext()) {
				soln = results.nextSolution();
				// process query here
				{
					label = soln.getLiteral("com").getLexicalForm();
				}
			}
			return label;
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}

	private String getEnglishLabel(String resource) {

		if (resource.equals(RDF.type.getURI())) {
			return "type";
		} else if (resource.equals(RDFS.label.getURI())) {
			return "label";
		}
		try {
			String labelQuery = "SELECT ?label WHERE {<" + resource + "> "
					+ "<http://www.w3.org/2000/01/rdf-schema#label> ?label. FILTER (lang(?label) = 'en')}";

			// take care of graph issues. Only takes one graph. Seems like some
			// sparql endpoint do
			// not like the FROM option.
			ResultSet results = new SparqlQuery(labelQuery, endpoint).send();

			// get label from knowledge base
			String label = null;
			QuerySolution soln;
			while (results.hasNext()) {
				soln = results.nextSolution();
				// process query here
				{
					label = soln.getLiteral("label").getLexicalForm();
				}
			}
			// System.out.println("Label: " + label);
			return label;
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}

	private boolean annotateDocument(Document document, Resource resource) {
		String label = getEnglishLabel(resource.getURI());
		if (label == null) {
			LOGGER.info("Couldn't find an English label for " + resource.toString() + ". Returning null.");
			return false;
		}
		String text = document.getText();
		int pos = text.indexOf(label);
		if (pos < 0) {
			LOGGER.info("Couldn't find the label \"{}\" inside the given text \"{}\". Returning false.", label, text);
			// return false;
		} else {
			document.addMarking(new NamedEntity(pos, label.length(), resource.getURI()));
		}
		return true;
	}

	private boolean annotateDocument(Document document, Resource subject, Resource object) {
		// we have to find out which label is the longer one and start with it
		String label1 = getEnglishLabel(subject.getURI());
		if (label1 == null) {
			LOGGER.info("Couldn't find an English label for " + subject.toString() + ". Returning null.");
			return false;
		}
		String label2 = getEnglishLabel(object.getURI());
		if (label2 == null) {
			LOGGER.info("Couldn't find an English label for " + object.toString() + ". Returning null.");
			return false;
		}
		Resource r1, r2;
		if (label1.length() > label2.length()) {
			r1 = subject;
			r2 = object;
		} else {
			String temp = label1;
			label1 = label2;
			label2 = temp;
			r2 = subject;
			r1 = object;
		}
		// Now, label1 is the longer label and r1 is its resource
		// Let's find the larger label inside the text
		String text = document.getText();
		BitSet blockedPositions = new BitSet(text.length());
		int pos1 = text.indexOf(label1);
		if (pos1 < 0) {
			LOGGER.info("Couldn't find the label \"{}\" inside the given text \"{}\". Returning false.", label1, text);
			// return false;
		} else {
			document.addMarking(new NamedEntity(pos1, label1.length(), r1.getURI()));
			blockedPositions.set(pos1, pos1 + label1.length());
		}
		// Let's find the smaller label inside the text. We have to make sure
		// that it is not inside of the larger label's marking.

		int pos2 = -label2.length();
		do {
			pos2 = text.indexOf(label2, pos2 + label2.length());
			while (pos2 < 0) {
				LOGGER.info("Couldn't find the label \"{}\" inside the given text \"{}\". Returning false.", label1,
						text);
				return false;
			}
			// repeat while the position found is inside the marking of label1.
			// Since label1 is larger, we can simply check whether the beginning
			// or the end of label2 is blocked by the first label.
		} while (blockedPositions.get(pos2) || blockedPositions.get(pos2 + label2.length()));
		document.addMarking(new NamedEntity(pos2, label2.length(), r2.getURI()));

		return true;
	}

	public static void main(String args[]) {
		Set<String> classes = new HashSet<>();
		classes.add("<http://dbpedia.org/ontology/Person>");
		classes.add("<http://dbpedia.org/ontology/Place>");
		classes.add("<http://dbpedia.org/ontology/Organisation>");
		// TripleSelector ts = new SimpleSummarySelector(classes, classes,
		// "http://dbpedia.org/sparql", null);
		SemWeb2NLVerbalizer verbalizer = new SemWeb2NLVerbalizer(SparqlEndpoint.getEndpointDBpedia(), true, true);

		List<Statement> stmts = Arrays.asList(
				new StatementImpl(new ResourceImpl("http://dbpedia.org/resource/Streaky_Bay,_South_Australia"),
						new PropertyImpl("http://dbpedia.org/property/fedgov"),
						new ResourceImpl("http://dbpedia.org/resource/Division_of_Grey")),
				new StatementImpl(new ResourceImpl("http://dbpedia.org/resource/Streaky_Bay,_South_Australia"),
						new PropertyImpl("http://dbpedia.org/property/location"),
						new ResourceImpl("http://dbpedia.org/resource/Adelaide")),
				new StatementImpl(new ResourceImpl("http://dbpedia.org/resource/Streaky_Bay,_South_Australia"),
						new PropertyImpl("http://dbpedia.org/ontology/country"),
						new ResourceImpl("http://dbpedia.org/resource/Australia")));

		Document doc = verbalizer.generateDocument(stmts);
		System.out.println(doc);
		// for (int i = 0; i <= 5; i++) {
		// List<Statement> stmts = ts.getNextStatements();
		// Document doc = verbalizer.generateDocument(stmts);
		// System.out.println(doc);
		// }
	}

	@Override
	public int compare(NamedEntity n1, NamedEntity n2) {
		int diff = n1.getLength() - n2.getLength();
		if (diff < 0) {
			// n1 is shorter
			return 1;
		} else if (diff > 0) {
			return -1;
		} else {
			return 0;
		}
	}

}
