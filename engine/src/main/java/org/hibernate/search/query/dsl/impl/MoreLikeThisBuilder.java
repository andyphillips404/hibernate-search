/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * Copyright (c) 2014, Red Hat, Inc. and/or its affiliates or third-party contributors as
 * indicated by the @author tags or express copyright attribution
 * statements applied by the authors.  All third-party contributions are
 * distributed under license by Red Hat, Inc.
 *
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, write to:
 * Free Software Foundation, Inc.
 * 51 Franklin Street, Fifth Floor
 * Boston, MA  02110-1301  USA
 */

package org.hibernate.search.query.dsl.impl;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.Fields;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.queries.mlt.MoreLikeThis;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.similarities.TFIDFSimilarity;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.CharsRef;
import org.apache.lucene.util.IOUtils;
import org.apache.lucene.util.PriorityQueue;
import org.apache.lucene.util.UnicodeUtil;

import org.hibernate.search.bridge.FieldBridge;
import org.hibernate.search.bridge.builtin.NumericFieldBridge;
import org.hibernate.search.bridge.util.impl.ContextualExceptionBridgeHelper;
import org.hibernate.search.engine.spi.DocumentBuilderIndexedEntity;
import org.hibernate.search.engine.spi.SearchFactoryImplementor;
import org.hibernate.search.exception.AssertionFailure;
import org.hibernate.search.util.impl.PassThroughAnalyzer;
import org.hibernate.search.util.logging.impl.Log;
import org.hibernate.search.util.logging.impl.LoggerFactory;

/**
 * Class inspired and code copied from Apache Lucene MoreLikeThis class.
 * Apache Lucene code copyright the Apache Software Foundation released under the
 * Apache Software License 2.0.
 *
 * @author Emmanuel Bernard <emmanuel@hibernate.org>
 */
public class MoreLikeThisBuilder<T> {

	private static final Log log = LoggerFactory.make();

	private int minWordLen = MoreLikeThis.DEFAULT_MIN_WORD_LENGTH;
	private int maxNumTokensParsed = MoreLikeThis.DEFAULT_MAX_NUM_TOKENS_PARSED;
	private int maxWordLen = MoreLikeThis.DEFAULT_MAX_WORD_LENGTH;
	private Set<?> stopWords = MoreLikeThis.DEFAULT_STOP_WORDS;
	private DocumentBuilderIndexedEntity<T> documentBuilder;
	// We lower the min defaults to 1 because we don't merge the freq of *all* fields unlike the original MoreLikeThis
	// TODO: is that hurting performance? Could we guess "small fields" and ony lower these?
	private int minTermFreq = 1; //MoreLikeThis.DEFAULT_MIN_TERM_FREQ;
	private int minDocFreq = 1; //MoreLikeThis.DEFAULT_MIN_DOC_FREQ;
	private int maxDocFreq = MoreLikeThis.DEFAULT_MAX_DOC_FREQ;
	private int maxQueryTerms = MoreLikeThis.DEFAULT_MAX_QUERY_TERMS;
	private boolean boost = MoreLikeThis.DEFAULT_BOOST;
	private float boostFactor = 1;
	private TFIDFSimilarity similarity;
	private Integer documentNumber;
	private String[] compatibleFieldNames;
	private IndexReader indexReader;
	private FieldsContext fieldsContext;
	private Object input;
	private QueryBuildingContext queryContext;

	public MoreLikeThisBuilder( DocumentBuilderIndexedEntity<T> documentBuilder, SearchFactoryImplementor searchFactory ) {
		log.requireTFIDFSimilarity( documentBuilder.getBeanClass() );
		this.documentBuilder = documentBuilder;
		this.similarity = (TFIDFSimilarity) searchFactory.getIndexBindings().get( documentBuilder.getBeanClass() ).getSimilarity();
	}

	public MoreLikeThisBuilder indexReader(IndexReader indexReader) {
		this.indexReader = indexReader;
		return this;
	}

	public MoreLikeThisBuilder compatibleFieldNames(String... compatibleFieldNames) {
		this.compatibleFieldNames = compatibleFieldNames;
		return this;
	}

	public MoreLikeThisBuilder documentNumber(Integer docNum) {
		this.documentNumber = docNum;
		return this;
	}

	public MoreLikeThisBuilder otherMoreLikeThisContext(MoreLikeThisQueryContext moreLikeThisContext) {
		this.boost = moreLikeThisContext.isBoostTerms();
		this.boostFactor = moreLikeThisContext.getTermBoostFactor();
		return this;
	}

	/**
	 * Return a query that will return docs like the passed lucene document ID.
	 */
	public Query createQuery() {
		try {
			return createQuery( retrieveTerms() );
		}
		catch (IOException e) {
			throw log.ioExceptionOnIndexOfEntity( e, documentBuilder.getBeanClass() );
		}
	}

	/**
	 * Create the More Like This query from a PriorityQueue
	 */
	private Query createQuery(List<PriorityQueue<Object[]>> q) {
		//In the original algorithm, the number of terms is limited to maxQueryTerms
		//In the current implementation, we do nbrOfFields * maxQueryTerms
		int length = fieldsContext.size();
		if ( length == 0 ) {
			throw new AssertionFailure( "Querying MoreLikeThis on 0 field." );
		}
		else if ( length == 1 ) {
			return createQuery( q.get( 0 ), fieldsContext.getFirst() );
		}
		else {
			BooleanQuery query = new BooleanQuery();
			//the fieldsContext indexes are aligned with the priority queue's
			Iterator<FieldContext> fieldsContextIterator = fieldsContext.iterator();
			for ( PriorityQueue<Object[]> queue : q ) {
				try {
					query.add( createQuery( queue, fieldsContextIterator.next() ), BooleanClause.Occur.SHOULD );
				}
				catch (BooleanQuery.TooManyClauses ignore) {
					break;
				}
			}
			return query;
		}
	}

	private Query createQuery(PriorityQueue<Object[]> q, FieldContext fieldContext) {
		if ( q == null ) {
			final FieldBridge fieldBridge = fieldContext.getFieldBridge() != null ? fieldContext.getFieldBridge() : documentBuilder.getBridge( fieldContext.getField() );
			if ( fieldBridge instanceof NumericFieldBridge ) {
				// we probably can do something here
				//TODO how to build the query where we don't have the value?
			}
			throw log.fieldCannotBeUsedInMoreLikeThis( fieldContext.getField(), documentBuilder.getBeanClass() );
		}

		BooleanQuery query = new BooleanQuery();
		Object cur;
		int qterms = 0;
		float bestScore = 0;
		while ( ( cur = q.pop() ) != null ) {
			Object[] ar = (Object[]) cur;
			TermQuery tq = new TermQuery( new Term( (String) ar[1], (String) ar[0] ) );

			if ( boost ) {
				if ( qterms == 0 ) {
					bestScore = ( (Float) ar[2]);
				}
				float myScore = ( (Float) ar[2]);

				tq.setBoost( boostFactor * myScore / bestScore );
			}

			try {
				query.add( tq, BooleanClause.Occur.SHOULD );
			}
			catch (BooleanQuery.TooManyClauses ignore) {
				break;
			}

			qterms++;
			if ( maxQueryTerms > 0 && qterms >= maxQueryTerms ) {
				break;
			}
		}
		// Apply field adjustments
		return fieldContext.getFieldCustomizer().setWrappedQuery( query ).createQuery();
	}

	/**
	 * Find words for a more-like-this query former.
	 * Store them per field name according to the order of fieldnames defined in {@link #fieldsContext}.
	 * If the field name is not compatible with term retrieval, the queue will be empty for that index.
	 */
	private List<PriorityQueue<Object[]>> retrieveTerms() throws IOException {
		int size = fieldsContext.size();
		Map<String,Map<String, Int>> termFreqMapPerFieldname = new HashMap<String,Map<String, Int>>( size );
		final Fields vectors;
		Document maybeDocument = null;
		if ( documentNumber == null && size > 0 ) {
			//build the document from the entity instance

			//first build the list of fields we are interested in
			String[] fieldNames = new String[ size ];
			Iterator<FieldContext> fieldsContextIterator = fieldsContext.iterator();
			for ( int index = 0 ; index < size ; index++ ) {
				fieldNames[index] = fieldsContextIterator.next().getField();
			}
			//TODO should we keep the fieldToAnalyzerMap around to pass to the analyzer?
			Map<String,String> fieldToAnalyzerMap = new HashMap<String, String>( );
			//FIXME by calling documentBuilder we don't honor .comparingField("foo").ignoreFieldBridge(): probably not a problem in practice though
			maybeDocument = documentBuilder.getDocument( (T) input, null, fieldToAnalyzerMap, null, new ContextualExceptionBridgeHelper(), fieldNames );
			vectors = null;
		}
		else {
			vectors = indexReader.getTermVectors( documentNumber );
		}
		for ( FieldContext fieldContext : fieldsContext ) {
			String fieldName = fieldContext.getField();
			if ( isCompatibleField( fieldName ) ) {
				Map<String,Int> termFreqMap = new HashMap<String, Int>();
				termFreqMapPerFieldname.put( fieldName, termFreqMap );
				final Terms vector;
				if ( vectors != null ) {
					vector = vectors.terms( fieldName );
				}
				else {
					vector = null;
				}

				// field does not store term vector info
				if ( vector == null ) {
					if ( maybeDocument == null ) {
						maybeDocument = indexReader.document( documentNumber );
					}
					IndexableField[] fields = maybeDocument.getFields( fieldName );
					for ( IndexableField field : fields ) {
						//TODO how can I read compressed data
						//TODO numbers?
						final String stringValue = field.stringValue();
						if ( stringValue != null ) {
							addTermFrequencies( new StringReader( stringValue ), termFreqMap, fieldContext );
						}
					}
				}
				else {
					addTermFrequencies( termFreqMap, vector );
				}
			}
			else {
				//place null as the field is not compatible
				termFreqMapPerFieldname.put( fieldName, null );
			}
		}
		List<PriorityQueue<Object[]>> results = new ArrayList<PriorityQueue<Object[]>>( size );
		for ( Map.Entry<String,Map<String,Int>> entry : termFreqMapPerFieldname.entrySet() ) {
			results.add( createQueue( entry.getKey(), entry.getValue() ) );
		}
		return results;
	}

	private boolean isCompatibleField(String fieldName) {
		for ( String compatibleFieldName : compatibleFieldNames ) {
			if ( compatibleFieldName.equals( fieldName ) ) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Create a PriorityQueue from a word->tf map.
	 *
	 * @param words a map of words keyed on the word(String) with Int objects as the values.
	 */
	private PriorityQueue<Object[]> createQueue(String fieldName, Map<String, Int> words) throws IOException {
		if ( words == null ) {
			//incompatible field name
			return null;
		}
		// have collected all words in doc and their freqs
		int numDocs = indexReader.numDocs();
		FreqQ res = new FreqQ( words.size() ); // will order words by score

		for ( Map.Entry<String,Int> entry : words.entrySet() ) { // for every word
			String word = entry.getKey();
			int tf = entry.getValue().x; // term freq in the source doc
			if ( minTermFreq > 0 && tf < minTermFreq ) {
				continue; // filter out words that don't occur enough times in the source
			}

			// The original algorithm looks for all field names and finds the top frequency
			// and only consider this field for the query
			// "go through all the fields and find the largest document frequency"
			Term term = new Term( fieldName, word );
			int freq = indexReader.docFreq( new Term( fieldName, word ) );

			if ( minDocFreq > 0 && freq < minDocFreq ) {
				continue; // filter out words that don't occur in enough docs
			}

			if ( freq > maxDocFreq ) {
				continue; // filter out words that occur in too many docs
			}

			if ( freq == 0 ) {
				continue; // index update problem?
			}

			float idf = similarity.idf( freq, numDocs );
			float score = tf * idf;

			// only really need 1st 3 entries, other ones are for troubleshooting
			res.insertWithOverflow(
					new Object[] {
							word, // the word
							fieldName, // the top field
							score, // overall score
							idf, // idf
							freq, // freq in all docs
							tf
					}
			);
		}
		return res;
	}

	/**
	 * Adds terms and frequencies found in vector into the Map termFreqMap
	 *
	 * @param termFreqMap a Map of terms and their frequencies
	 * @param vector List of terms and their frequencies for a doc/field
	 */
	private void addTermFrequencies(Map<String, Int> termFreqMap, Terms vector) throws IOException {
		final TermsEnum termsEnum = vector.iterator( null );
		final CharsRef spare = new CharsRef();
		BytesRef text;
		while ( ( text = termsEnum.next() ) != null ) {
			UnicodeUtil.UTF8toUTF16( text, spare );
			final String term = spare.toString();
			if ( isNoiseWord( term ) ) {
				continue;
			}
			final int freq = (int) termsEnum.totalTermFreq();

			// increment frequency
			Int cnt = termFreqMap.get( term );
			if ( cnt == null ) {
				cnt = new Int();
				termFreqMap.put( term, cnt );
				cnt.x = freq;
			}
			else {
				cnt.x += freq;
			}
		}
	}

	/**
	 * Adds term frequencies found by tokenizing text from reader into the Map words
	 *
	 * @param r a source of text to be tokenized
	 * @param termFreqMap a Map of terms and their frequencies
	 * @param fieldName Used by analyzer for any special per-field analysis
	 */
	private void addTermFrequencies(Reader r, Map<String, Int> termFreqMap, FieldContext fieldContext)
			throws IOException {
		String fieldName = fieldContext.getField();
		Analyzer analyzer = queryContext.getQueryAnalyzer();
		//TODO The original MLT implementation forces fields with analyzers. Seems that a pass through makes sense.
		//analyzer = analyzer != null ? analyzer : PassThroughAnalyzer.INSTANCE;
		if ( fieldContext.isIgnoreAnalyzer() ) {
			// essentially does the Reader to String conversion for us
			analyzer = PassThroughAnalyzer.INSTANCE;
		}
		TokenStream ts = analyzer.tokenStream( fieldName, r );
		try {
			int tokenCount = 0;
			// for every token
			CharTermAttribute termAtt = ts.addAttribute( CharTermAttribute.class );
			ts.reset();
			while ( ts.incrementToken() ) {
				String word = termAtt.toString();
				tokenCount++;
				if ( tokenCount > maxNumTokensParsed ) {
					break;
				}
				if ( isNoiseWord( word ) ) {
					continue;
				}

				// increment frequency
				Int cnt = termFreqMap.get( word );
				if ( cnt == null ) {
					termFreqMap.put( word, new Int() );
				}
				else {
					cnt.x++;
				}
			}
			ts.end();
		}
		finally {
			IOUtils.closeWhileHandlingException( ts );
		}
	}

	/**
	 * determines if the passed term is likely to be of interest in "more like" comparisons
	 *
	 * @param term The word being considered
	 *
	 * @return true if should be ignored, false if should be used in further analysis
	 */
	private boolean isNoiseWord(String term) {
		int len = term.length();
		if ( minWordLen > 0 && len < minWordLen ) {
			return true;
		}
		if ( maxWordLen > 0 && len > maxWordLen ) {
			return true;
		}
		return stopWords != null && stopWords.contains( term );
	}

	public MoreLikeThisBuilder fieldsContext(FieldsContext fieldsContext) {
		this.fieldsContext = fieldsContext;
		return this;
	}

	public MoreLikeThisBuilder input(Object input) {
		this.input = input;
		return this;
	}

	public MoreLikeThisBuilder queryContext(QueryBuildingContext queryContext) {
		this.queryContext = queryContext;
		return this;
	}

	/**
	 * PriorityQueue that orders words by score.
	 */
	private static class FreqQ extends PriorityQueue<Object[]> {
		FreqQ(int s) {
			super( s );
		}

		@Override
		protected boolean lessThan(Object[] aa, Object[] bb) {
			Float fa = (Float) aa[2];
			Float fb = (Float) bb[2];
			return fa > fb;
		}
	}

	/**
	 * Use for frequencies and to avoid renewing Integers.
	 */
	private static class Int {
		int x;

		Int() {
			x = 1;
		}

		@Override
		public String toString() {
			return "Int{" + x + '}';
		}
	}
}