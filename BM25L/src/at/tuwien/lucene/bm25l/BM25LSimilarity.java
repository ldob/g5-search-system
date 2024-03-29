package at.tuwien.lucene.bm25l;

import java.io.IOException;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.FieldInvertState;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.search.CollectionStatistics;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.TermStatistics;
import org.apache.lucene.search.similarities.Similarity;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.SmallFloat;

/**
 * BM25L Similarity. 
 */
public class BM25LSimilarity extends Similarity {
	private final float k1;
	private final float b;
	private final float delta;

	/**
	 * BM25 with the supplied parameter values.
	 * @param k1 Controls non-linear term frequency normalization (saturation).
	 * @param b Controls to what degree document length normalizes tf values.
	 * @param delta BM25L parameter to boost long documents
	 */
	public BM25LSimilarity(float k1, float b, float delta) {
		this.k1 = k1;
		this.b  = b;
		this.delta = delta;
	}
	
	/** BM25L with these default values:
	 * <ul>
	 *   <li>{@code k1 = 1.2}</li>
	 *   <li>{@code b = 0.75}</li>
	 * </ul>
	 */
	public BM25LSimilarity(float delta) {
		this.k1 = 1.2f;
		this.b  = 0.75f;
		this.delta = delta;
	}

	/** BM25L with these default values:
	 * <ul>
	 *   <li>{@code k1 = 1.2}</li>
	 *   <li>{@code b = 0.75}</li>
	 *   <li>{@code delta = 0.5}</li>
	 * </ul>
	 */
	public BM25LSimilarity() {
		this.k1 = 1.2f;
		this.b  = 0.75f;
		this.delta = 0.5f;
	}

	/** Implemented as <code>log(1 + (numDocs - docFreq + 0.5)/(docFreq + 0.5))</code>. */
	protected float idf(long docFreq, long numDocs) {
		return (float) Math.log(1 + (numDocs - docFreq + 0.5D)/(docFreq + 0.5D));
	}

	/** Implemented as <code>1 / (distance + 1)</code>. */
	protected float sloppyFreq(int distance) {
		return 1.0f / (distance + 1);
	}

	/** The default implementation returns <code>1</code> */
	protected float scorePayload(int doc, int start, int end, BytesRef payload) {
		return 1;
	}

	/** The default implementation computes the average as <code>sumTotalTermFreq / maxDoc</code>,
	 * or returns <code>1</code> if the index does not store sumTotalTermFreq:
	 * any field that omits frequency information). */
	protected float avgFieldLength(CollectionStatistics collectionStats) {
		final long sumTotalTermFreq = collectionStats.sumTotalTermFreq();
		if (sumTotalTermFreq <= 0) {
			return 1f;       // field does not exist, or stat is unsupported
		} else {
			return (float) (sumTotalTermFreq / (double) collectionStats.maxDoc());
		}
	}

	/** The default implementation encodes <code>boost / sqrt(length)</code>
	 * with {@link SmallFloat#floatToByte315(float)}.  This is compatible with 
	 * Lucene's default implementation.  If you change this, then you should 
	 * change {@link #decodeNormValue(byte)} to match. */
	protected byte encodeNormValue(float boost, int fieldLength) {
		return SmallFloat.floatToByte315(boost / (float) Math.sqrt(fieldLength));
	}

	/** The default implementation returns <code>1 / f<sup>2</sup></code>
	 * where <code>f</code> is {@link SmallFloat#byte315ToFloat(byte)}. */
	protected float decodeNormValue(byte b) {
		return NORM_TABLE[b & 0xFF];
	}

	/** 
	 * True if overlap tokens (tokens with a position of increment of zero) are
	 * discounted from the document's length.
	 */
	protected boolean discountOverlaps = true;

	/** Sets whether overlap tokens (Tokens with 0 position increment) are 
	 *  ignored when computing norm.  By default this is true, meaning overlap
	 *  tokens do not count when computing norms. */
	public void setDiscountOverlaps(boolean v) {
		discountOverlaps = v;
	}

	/**
	 * Returns true if overlap tokens are discounted from the document's length. 
	 * @see #setDiscountOverlaps 
	 */
	public boolean getDiscountOverlaps() {
		return discountOverlaps;
	}

	/** Cache of decoded bytes. */
	private static final float[] NORM_TABLE = new float[256];

	static {
		for (int i = 0; i < 256; i++) {
			float f = SmallFloat.byte315ToFloat((byte)i);
			NORM_TABLE[i] = 1.0f / (f*f);
		}
	}

	@Override
	public final long computeNorm(FieldInvertState state) {
		final int numTerms = discountOverlaps ? state.getLength() - state.getNumOverlap() : state.getLength();
		return encodeNormValue(state.getBoost(), numTerms);
	}

	/**
	 * Computes a score factor for a simple term and returns an explanation
	 * for that score factor.
	 * 
	 * <p>
	 * The default implementation uses:
	 * 
	 * <pre class="prettyprint">
	 * idf(docFreq, searcher.maxDoc());
	 * </pre>
	 * 
	 * Note that {@link CollectionStatistics#maxDoc()} is used instead of
	 * {@link org.apache.lucene.index.IndexReader#numDocs() IndexReader#numDocs()} because also 
	 * {@link TermStatistics#docFreq()} is used, and when the latter 
	 * is inaccurate, so is {@link CollectionStatistics#maxDoc()}, and in the same direction.
	 * In addition, {@link CollectionStatistics#maxDoc()} is more efficient to compute
	 *   
	 * @param collectionStats collection-level statistics
	 * @param termStats term-level statistics for the term
	 * @return an Explain object that includes both an idf score factor 
             and an explanation for the term.
	 */
	public Explanation idfExplain(CollectionStatistics collectionStats, TermStatistics termStats) {
		final long df = termStats.docFreq();
		final long max = collectionStats.maxDoc();
		final float idf = idf(df, max);
		return new Explanation(idf, "idf(docFreq=" + df + ", maxDocs=" + max + ")");
	}

	/**
	 * Computes a score factor for a phrase.
	 * 
	 * <p>
	 * The default implementation sums the idf factor for
	 * each term in the phrase.
	 * 
	 * @param collectionStats collection-level statistics
	 * @param termStats term-level statistics for the terms in the phrase
	 * @return an Explain object that includes both an idf 
	 *         score factor for the phrase and an explanation 
	 *         for each term.
	 */
	public Explanation idfExplain(CollectionStatistics collectionStats, TermStatistics termStats[]) {
		final long max = collectionStats.maxDoc();
		float idf = 0.0f;
		final Explanation exp = new Explanation();
		exp.setDescription("idf(), sum of:");
		for (final TermStatistics stat : termStats ) {
			final long df = stat.docFreq();
			final float termIdf = idf(df, max);
			exp.addDetail(new Explanation(termIdf, "idf(docFreq=" + df + ", maxDocs=" + max + ")"));
			idf += termIdf;
		}
		exp.setValue(idf);
		return exp;
	}

	@Override
	public final SimWeight computeWeight(float queryBoost, CollectionStatistics collectionStats, TermStatistics... termStats) {
		Explanation idf = termStats.length == 1 ? idfExplain(collectionStats, termStats[0]) : idfExplain(collectionStats, termStats);

		float avgdl = avgFieldLength(collectionStats);

		return new BM25LStats(collectionStats.field(), idf, queryBoost, avgdl);
	}

	@Override
	public final SimScorer simScorer(SimWeight weight, LeafReaderContext context) throws IOException {
		BM25LStats bm25stats = (BM25LStats) weight;
		return new BM25LDocScorer(bm25stats, context.reader().getNormValues(bm25stats.field));
	}

	private class BM25LDocScorer extends SimScorer {
		private final BM25LStats stats;
		private final float weightValue; // boost * idf * (k1 + 1)
		private final NumericDocValues norms;

		BM25LDocScorer(BM25LStats stats, NumericDocValues norms) throws IOException {
			this.stats = stats;
			this.weightValue = stats.weight * (k1 + 1);
			this.norms = norms;
		}

		@Override
		public float score(int doc, float freq) {
			// BM25L Score function
			// normalized term frequency: c'(q,D) = c(q,D) / (1 - b + b * |D|/avdl)
			
			
			float docLength = decodeNormValue((byte)norms.get(doc));
			
			float normalizedFreq = freq / (1 - b + b * docLength / stats.avgdl);
			
			if (normalizedFreq > 0) {
				// idf * ((k1 +1) * [c' + delta]) / (k1 + [c' + delta])
				return stats.idf.getValue() * (k1+1) * (normalizedFreq + delta) / (k1 + (normalizedFreq + delta));
			}
			else {
				return 0;
			}

		}

		@Override
		public Explanation explain(int doc, Explanation freq) {
			return explainScore(doc, freq, stats, norms);
		}

		@Override
		public float computeSlopFactor(int distance) {
			return sloppyFreq(distance);
		}

		@Override
		public float computePayloadFactor(int doc, int start, int end, BytesRef payload) {
			return scorePayload(doc, start, end, payload);
		}
	}

	/** Collection statistics for the BM25 model. */
	private static class BM25LStats extends SimWeight {
		/** BM25's idf */
		private final Explanation idf;
		/** The average document length. */
		private final float avgdl;
		/** query's inner boost */
		private final float queryBoost;
		/** query's outer boost (only for explain) */
		private float topLevelBoost;
		/** weight (idf * boost) */
		private float weight;
		/** field name, for pulling norms */
		private final String field;

		BM25LStats(String field, Explanation idf, float queryBoost, float avgdl) {
			this.field = field;
			this.idf = idf;
			this.queryBoost = queryBoost;
			this.avgdl = avgdl;
		}

		@Override
		public float getValueForNormalization() {
			// we return a TF-IDF like normalization to be nice, but we don't actually normalize ourselves.
			final float queryWeight = idf.getValue() * queryBoost;
			return queryWeight * queryWeight;
		}

		@Override
		public void normalize(float queryNorm, float topLevelBoost) {
			// we don't normalize with queryNorm at all, we just capture the top-level boost
			this.topLevelBoost = topLevelBoost;
			this.weight = idf.getValue() * queryBoost * topLevelBoost;
		} 
	}

	private Explanation explainScore(int doc, Explanation freq, BM25LStats stats, NumericDocValues norms) {
		Explanation result = new Explanation();
		result.setDescription("score(doc="+doc+",freq="+freq+"), product of:");

		result.addDetail(stats.idf);
		
		Explanation tfNormExpl = new Explanation();
		tfNormExpl.setDescription("tfNorm, computed from:");
		tfNormExpl.addDetail(freq);
		tfNormExpl.addDetail(new Explanation(k1, "parameter k1"));
		tfNormExpl.addDetail(new Explanation(delta, "parameter delta"));	
		tfNormExpl.addDetail(new Explanation(b, "parameter b"));
		tfNormExpl.addDetail(new Explanation(stats.avgdl, "avgDocumentLength"));
		float docLength = decodeNormValue((byte)norms.get(doc));
		tfNormExpl.addDetail(new Explanation(docLength, "documentLength"));
			
		//additional delta parameter
		float cS = freq.getValue() / (1 - b + b * docLength/stats.avgdl);
		float normalizedFreq = (k1+1) * (cS + delta) / (k1 + (cS + delta));
		
		tfNormExpl.setValue(normalizedFreq);
		
		result.addDetail(tfNormExpl);
		result.setValue(stats.idf.getValue() * tfNormExpl.getValue());
		return result;
	}

	@Override
	public String toString() {
		return "BM25L(k1=" + k1 + ",b=" + b + ",delta=" + delta + ")";
	}

	/** 
	 * Returns the <code>k1</code> parameter
	 * @see #BM25Similarity(float, float) 
	 */
	public float getK1() {
		return k1;
	}

	/**
	 * Returns the <code>b</code> parameter 
	 * @see #BM25Similarity(float, float) 
	 */
	public float getB() {
		return b;
	}
}