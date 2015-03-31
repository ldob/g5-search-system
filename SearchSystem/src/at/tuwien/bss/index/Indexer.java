package at.tuwien.bss.index;

import java.io.IOException;
import java.util.List;

import at.tuwien.bss.documents.DocumentCollection;
import at.tuwien.bss.logging.SSLogger;
import at.tuwien.bss.parse.Parser;

public abstract class Indexer {
	
	private static final SSLogger LOGGER = SSLogger.getLogger();
	
	private Index index = new Index();
	
	protected abstract List<String> segment(List<String> terms);
	
	/**
	 * Index a DocumentCollection.
	 * @param collection
	 * @param max Maximum Number of Documents to index (for test purposes TODO remove)
	 * @throws IOException
	 */
	public void index(DocumentCollection collection, int max) throws IOException {
		
		Parser parser = new Parser();
				
		int count = Math.min(collection.getCount(), max);
		for (int documentId=0; documentId<count; documentId++) {
			
			// get content, parse and segment
			String document = collection.getContent(documentId);
			List<String> terms = parser.parse(document);
			List<String> segmentedTerms = segment(terms);
			
			// insert terms into index
			for(String term : segmentedTerms) {
				index.add(term, documentId);
			}
		}
		
		index.setDocumentCount(count);
		
		// calculate the Tf-Idf Weighting
		index.calculateWeighting(new WeightingTfIdf());
	}
	
	public Index getIndex() {
		return index;
	}
}
