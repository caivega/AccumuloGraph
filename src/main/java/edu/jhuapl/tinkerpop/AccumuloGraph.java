/* Copyright 2014 The Johns Hopkins University Applied Physics Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.jhuapl.tinkerpop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedSet;

import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.BatchDeleter;
import org.apache.accumulo.core.client.BatchScanner;
import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.IteratorSetting;
import org.apache.accumulo.core.client.MutationsRejectedException;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.client.admin.TableOperations;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.iterators.user.RegExFilter;
import org.apache.accumulo.core.util.PeekingIterator;
import org.apache.commons.configuration.Configuration;
import org.apache.hadoop.io.Text;

import com.tinkerpop.blueprints.Edge;
import com.tinkerpop.blueprints.Element;
import com.tinkerpop.blueprints.Features;
import com.tinkerpop.blueprints.Graph;
import com.tinkerpop.blueprints.GraphFactory;
import com.tinkerpop.blueprints.GraphQuery;
import com.tinkerpop.blueprints.Index;
import com.tinkerpop.blueprints.IndexableGraph;
import com.tinkerpop.blueprints.KeyIndexableGraph;
import com.tinkerpop.blueprints.Parameter;
import com.tinkerpop.blueprints.Vertex;
import com.tinkerpop.blueprints.util.DefaultGraphQuery;
import com.tinkerpop.blueprints.util.ExceptionFactory;

import edu.jhuapl.tinkerpop.cache.ElementCaches;
import edu.jhuapl.tinkerpop.mutator.Mutators;
import edu.jhuapl.tinkerpop.tables.EdgeIndexTableWrapper;
import edu.jhuapl.tinkerpop.tables.EdgeTableWrapper;
import edu.jhuapl.tinkerpop.tables.IndexNameTableWrapper;
import edu.jhuapl.tinkerpop.tables.IndexedKeysTableWrapper;
import edu.jhuapl.tinkerpop.tables.VertexIndexTableWrapper;
import edu.jhuapl.tinkerpop.tables.VertexTableWrapper;

/**
 * 
 * This is an implementation of TinkerPop's graph API
 * backed by Apache Accumulo. In addition to the basic
 * Graph interface, the implementation
 * supports {@link IndexableGraph} and {@link KeyIndexableGraph}.
 * 
 * <p/>Tables have the following formats.
 * 
 * <p/><strong>NOTE: This documentation is way out of date.</strong>
 * 
 * <p/>
 * <table border="1">
 *  <caption>Vertex table</caption>
 *  <thead>
 *    <tr><th>ROWID</th><th>COLFAM</th><th>COLQUAL</th><th>VALUE</th></tr>
 *  </thead>
 *  <tbody>
 *    <tr><td>VertexID</td><td>LABEL</td><td>EXISTS</td><td>[empty]</td></tr>
 *    <tr><td>VertexID</td><td>INEDGE</td><td>InVertexID_EdgeID</td><td>EdgeLabel</td></tr>
 *    <tr><td>VertexID</td><td>OUTEDGE</td><td>OutVertexID_EdgeID</td><td>EdgeLabel</td></tr>
 *    <tr><td>VertexID</td><td>PropertyKey</td><td>[empty]</td><td>Encoded Value</td></tr>
 *  </tbody>
 * </table>
 * 
 * <p/>
 * <table border="1">
 *  <caption>Edge table</caption>
 *  <thead>
 *    <tr> <th>ROWID</th><th>COLFAM</th><th>COLQUAL</th><th>VALUE</th></tr>
 *  </thead>
 *  <tbody>
 *    <tr><td>EdgeID</td><td>LABEL</td><td>[empty]</td><td>Encoded LabelValue</td></tr>
 *    <tr><td>EdgeID</td><td>INEDGE</td><td>InVertexID</td><td>[empty]</td></tr>
 *    <tr><td>EdgeID</td><td>OUTEDGE</td><td>OutVertexID</td><td>[empty]</td></tr>
 *    <tr><td>EdgeID</td><td>PropertyKey</td><td>[empty]</td><td>Encoded Value</td></tr>
 *  </tbody>
 * </table>
 * 
 * <p/>
 * <table border="1">
 *  <caption>Vertex / edge index tables (each index gets its own table)</caption>
 *  <thead>
 *    <tr><th>ROWID</th><th>COLFAM</th><th>COLQUAL</th><th>VALUE</th></tr>
 *  </thead>
 *  <tbody>
 *    <tr><td>Encoded Value</td><td>PropertyKey</td><td>ElementID</td><td>[empty]</td></tr>
 *  </tbody>
 * </table>
 * 
 * <p/>
 * <table border="1">
 *  <caption>Metadata/key metadata tables</caption>
 *  <thead>
 *    <tr><th>ROWID</th><th>COLFAM</th><th>COLQUAL</th><th>VALUE</th></tr>
 *  </thead>
 *  <tbody>
 *    <tr><td>IndexName</td><td>IndexClassType</td><td>[empty]</td><td>[empty]</td></tr>
 *  </tbody>
 * </table>
 */
public class AccumuloGraph implements Graph, KeyIndexableGraph, IndexableGraph {

  private GlobalInstances globals;

  /**
   * @deprecated Use {@link #globals} instead.
   */
  @Deprecated
  private AccumuloGraphConfiguration config;

  /**
   * @deprecated Remove when vertex functionality is gone.
   */
  @Deprecated
  private BatchWriter vertexBW;

  /**
   * Factory method for {@link GraphFactory}.
   */
  public static AccumuloGraph open(Configuration properties) throws AccumuloException {
    return new AccumuloGraph(properties);
  }

  /**
   * Instantiate from a generic {@link Configuration} populated
   * with appropriate AccumuloGraph parameters.
   * @param cfg
   */
  public AccumuloGraph(Configuration cfg) {
    this(new AccumuloGraphConfiguration(cfg));
  }

  /**
   * Main constructor.
   * @param config
   */
  public AccumuloGraph(AccumuloGraphConfiguration config) {
    config.validate();
    this.config = config;

    AccumuloGraphUtils.handleCreateAndClear(config);

    try {
      globals = new GlobalInstances(this, config,
          config.getConnector().createMultiTableBatchWriter(config
              .getBatchWriterConfig()), new ElementCaches(config));
    } catch (Exception e) {
      throw new AccumuloGraphException(e);
    }

    globals.setVertexWrapper(new VertexTableWrapper(globals));
    globals.setEdgeWrapper(new EdgeTableWrapper(globals));
    globals.setVertexIndexWrapper(new VertexIndexTableWrapper(globals));
    globals.setEdgeIndexWrapper(new EdgeIndexTableWrapper(globals));
    globals.setKeyMetadataWrapper(new IndexedKeysTableWrapper(globals));
    globals.setIndexMetadataWrapper(new IndexNameTableWrapper(globals));

    try {
      setupWriters();
    } catch (Exception e) {
      throw new AccumuloGraphException(e);
    }
  }

  private void setupWriters() throws Exception {
    vertexBW = globals.getMtbw().getBatchWriter(config.getVertexTableName());
  }

  protected Scanner getElementScanner(Class<? extends Element> type) {
    try {
      String tableName = config.getEdgeTableName();
      if (type.equals(Vertex.class))
        tableName = config.getVertexTableName();
      return config.getConnector().createScanner(tableName, config.getAuthorizations());
    } catch (Exception e) {
      throw new AccumuloGraphException(e);
    }
  }

  protected Scanner getScanner(String tablename) {
    try {
      return config.getConnector().createScanner(tablename,
          config.getAuthorizations());
    } catch (Exception e) {
      throw new AccumuloGraphException(e);
    }
  }

  // Aliases for the lazy
  private Scanner getMetadataScanner() {
    return getScanner(config.getIndexNamesTableName());
  }

  public Scanner getVertexIndexScanner() {
    return getScanner(config.getVertexKeyIndexTableName());
  }

  private Scanner getEdgeIndexScanner() {
    return getScanner(config.getEdgeKeyIndexTableName());
  }

  private BatchWriter getVertexIndexWriter() {
    return getWriter(config.getVertexKeyIndexTableName());
  }

  private BatchWriter getEdgeIndexWriter() {
    return getWriter(config.getEdgeKeyIndexTableName());
  }

  public BatchWriter getWriter(String tablename) {
    try {
      return globals.getMtbw().getBatchWriter(tablename);
    } catch (Exception e) {
      throw new AccumuloGraphException(e);
    }
  }

  private BatchScanner getElementBatchScanner(Class<? extends Element> type) {
    try {
      String tableName = config.getVertexTableName();
      if (type.equals(Edge.class))
        tableName = config.getEdgeTableName();
      BatchScanner x = config.getConnector().createBatchScanner(tableName, config.getAuthorizations(), config.getQueryThreads());
      x.setRanges(Collections.singletonList(new Range()));
      return x;
    } catch (Exception e) {
      throw new AccumuloGraphException(e);
    }
  }

  // End Aliases

  @Override
  public Features getFeatures() {
    return AccumuloFeatures.get();
  }

  @Override
  public Vertex addVertex(Object id) {
    if (id == null) {
      id = AccumuloGraphUtils.generateId();
    }

    String myID = id.toString();

    Vertex vert = null;
    if (!config.getSkipExistenceChecks()) {
      vert = getVertex(myID);
      if (vert != null) {
        throw ExceptionFactory.vertexWithIdAlreadyExists(myID);
      }
    }

    vert = new AccumuloVertex(globals, myID);

    globals.getVertexWrapper().writeVertex(vert);
    globals.checkedFlush();

    globals.getCaches().cache(vert, Vertex.class);

    return vert;
  }

  @Override
  public Vertex getVertex(Object id) {
    if (id == null) {
      throw ExceptionFactory.vertexIdCanNotBeNull();
    }
    String myID = id.toString();

    Vertex vertex = globals.getCaches().retrieve(myID, Vertex.class);
    if (vertex != null) {
      return vertex;
    }

    vertex = new AccumuloVertex(globals, myID);
    if (!config.getSkipExistenceChecks()) {
      // In addition to just an "existence" check, we will also load
      // any "preloaded" properties now, which saves us a round-trip
      // to Accumulo later.
      String[] preload = config.getPreloadedProperties();
      if (preload == null) {
        preload = new String[]{};
      }

      Map<String, Object> props = globals.getVertexWrapper().readProperties(vertex, preload);
      if (props == null) {
        return null;
      }
      ((AccumuloElement) vertex).cacheAllProperties(props);
    }

    globals.getCaches().cache(vertex, Vertex.class);

    return vertex;
  }

  @Override
  public void removeVertex(Vertex vertex) {
    globals.getCaches().remove(vertex.getId(), Vertex.class);

    if (!config.getIndexableGraphDisabled())
      clearIndex(vertex.getId());

    Scanner scan = getElementScanner(Vertex.class);
    scan.setRange(new Range(vertex.getId().toString()));

    BatchDeleter edgedeleter = null;
    BatchDeleter vertexdeleter = null;
    BatchWriter indexdeleter = getVertexIndexWriter();
    try {
      // Set up Deleters
      edgedeleter = config.getConnector().createBatchDeleter(config.getEdgeTableName(), config.getAuthorizations(), config.getQueryThreads(),
          config.getBatchWriterConfig());
      vertexdeleter = config.getConnector().createBatchDeleter(config.getVertexTableName(), config.getAuthorizations(), config.getQueryThreads(),
          config.getBatchWriterConfig());
    } catch (Exception e) {
      throw new AccumuloGraphException(e);
    }
    Iterator<Entry<Key,Value>> iter = scan.iterator();
    List<Range> ranges = new ArrayList<Range>();
    if (!iter.hasNext()) {
      throw ExceptionFactory.vertexWithIdDoesNotExist(vertex.getId());
    }
    try {
      // Search for edges
      while (iter.hasNext()) {
        Entry<Key,Value> e = iter.next();
        Key k = e.getKey();

        if (k.getColumnFamily().toString().equals(Constants.OUT_EDGE) ||
            k.getColumnFamily().toString().equals(Constants.IN_EDGE)) {
          ranges.add(new Range(k.getColumnQualifier().toString().split(Constants.ID_DELIM)[1]));

          Mutation vm = new Mutation(k.getColumnQualifier().toString().split(Constants.ID_DELIM)[0]);
          vm.putDelete(invert(k.getColumnFamily()), new Text(vertex.getId().toString() + Constants.ID_DELIM + k.getColumnQualifier().toString().split(Constants.ID_DELIM)[1]));
          vertexBW.addMutation(vm);
        } else {
          Mutation m = new Mutation(e.getValue().get());
          m.putDelete(k.getColumnFamily(), k.getRow());
          indexdeleter.addMutation(m);
        }

      }
      globals.checkedFlush();
      scan.close();

      // If Edges are found, delete the whole row
      if (!ranges.isEmpty()) {
        // TODO don't we also have to propagate these deletes to the
        // vertex index table?
        edgedeleter.setRanges(ranges);
        edgedeleter.delete();
        ranges.clear();
      }
      // Delete the whole vertex row
      ranges.add(new Range(vertex.getId().toString()));
      vertexdeleter.setRanges(ranges);
      vertexdeleter.delete();
    } catch (Exception e) {
      throw new AccumuloGraphException(e);
    } finally {
      if (edgedeleter != null)
        edgedeleter.close();
      if (vertexdeleter != null)
        vertexdeleter.close();
    }
  }

  // Maybe an Custom Iterator could make this better.
  private void clearIndex(Object id) {
    Iterable<Index<? extends Element>> it = this.getIndices();
    Iterator<Index<? extends Element>> iter = it.iterator();
    while (iter.hasNext()) {
      AccumuloIndex<?> in = (AccumuloIndex<?>) iter.next();
      String table = in.tableName;

      BatchDeleter del = null;
      try {
        del = config.getConnector().createBatchDeleter(table, config.getAuthorizations(), config.getMaxWriteThreads(), config.getBatchWriterConfig());
        del.setRanges(Collections.singleton(new Range()));
        StringBuilder regex = new StringBuilder();
        regex.append(".*\\Q").append(id.toString()).append("\\E$");

        IteratorSetting is = new IteratorSetting(10, "getEdgeFilter", RegExFilter.class);
        RegExFilter.setRegexs(is, null, null, regex.toString(), null, false);
        del.addScanIterator(is);
        del.delete();
      } catch (Exception e) {
        throw new AccumuloGraphException(e);
      } finally {
        if (del != null)
          del.close();
      }
    }
  }

  private Text invert(Text columnFamily) {
    return columnFamily.toString().equals(Constants.IN_EDGE) ?
        new Text(Constants.OUT_EDGE) : new Text(Constants.IN_EDGE);
  }

  @Override
  public Iterable<Vertex> getVertices() {
    return globals.getVertexWrapper().getVertices();
  }

  @Override
  public Iterable<Vertex> getVertices(String key, Object value) {
    AccumuloGraphUtils.validateProperty(key, value);
    if (config.getAutoIndex() || getIndexedKeys(Vertex.class).contains(key)) {
      // Use the index
      Scanner s = getVertexIndexScanner();
      byte[] val = AccumuloByteSerializer.serialize(value);
      Text tVal = new Text(val);
      s.setRange(new Range(tVal, tVal));
      s.fetchColumnFamily(new Text(key));

      return new ScannerIterable<Vertex>(s) {

        @Override
        public Vertex next(PeekingIterator<Entry<Key,Value>> iterator) {

          Key key = iterator.next().getKey();
          Vertex v = globals.getCaches().retrieve(key.getColumnQualifier().toString(), Vertex.class);

          v = (v == null ? new AccumuloVertex(globals, key.getColumnQualifier().toString()) : v);
          ((AccumuloElement) v).cacheProperty(key.getColumnFamily().toString(),
              AccumuloByteSerializer.deserialize(key.getRow().getBytes()));

          globals.getCaches().cache(v, Vertex.class);

          return v;
        }
      };
    } else {
      return globals.getVertexWrapper().getVertices(key, value);
    }
  }

  @Override
  public Edge addEdge(Object id, Vertex outVertex, Vertex inVertex, String label) {
    if (label == null) {
      throw ExceptionFactory.edgeLabelCanNotBeNull();
    }
    if (id == null) {
      id = AccumuloGraphUtils.generateId();
    }

    String myID = id.toString();

    AccumuloEdge edge = new AccumuloEdge(globals, myID, inVertex, outVertex, label);

    // TODO we arent suppose to make sure the given edge ID doesn't already
    // exist?

    globals.getEdgeWrapper().writeEdge(edge);
    globals.getVertexWrapper().writeEdgeEndpoints(edge);

    globals.checkedFlush();

    globals.getCaches().cache(edge, Edge.class);

    return edge;
  }

  @Override
  public Edge getEdge(Object id) {
    if (id == null) {
      throw ExceptionFactory.edgeIdCanNotBeNull();
    }
    String myID = id.toString();

    Edge edge = globals.getCaches().retrieve(myID, Edge.class);
    if (edge != null) {
      return edge;
    }

    edge = new AccumuloEdge(globals, myID);

    if (!config.getSkipExistenceChecks()) {
      // In addition to just an "existence" check, we will also load
      // any "preloaded" properties now, which saves us a round-trip
      // to Accumulo later.
      String[] preload = config.getPreloadedProperties();
      if (preload == null) {
        preload = new String[]{};
      }

      Map<String, Object> props = globals.getEdgeWrapper().readProperties(edge, preload);
      if (props == null) {
        return null;
      }
      ((AccumuloElement) edge).cacheAllProperties(props);
    }

    globals.getCaches().cache(edge, Edge.class);

    return edge;
  }

  @Override
  public void removeEdge(Edge edge) {
    if (!config.getIndexableGraphDisabled())
      clearIndex(edge.getId());

    globals.getCaches().remove(edge.getId(), Edge.class);

    Scanner s = getElementScanner(Edge.class);
    s.setRange(new Range(edge.getId().toString()));

    Iterator<Entry<Key,Value>> iter = s.iterator();
    Text inVert = null;
    Text outVert = null;
    List<Mutation> indexMutations = new ArrayList<Mutation>();
    while (iter.hasNext()) {
      Entry<Key,Value> e = iter.next();
      Key k = e.getKey();
      if (k.getColumnFamily().toString().equals(Constants.LABEL)) {
        String[] ids = k.getColumnQualifier().toString().split(Constants.ID_DELIM);
        inVert = new Text(ids[0]);
        outVert = new Text(ids[1]);
      } else {
        Mutation m = new Mutation(k.getColumnQualifier());
        m.putDelete(k.getColumnFamily(), k.getRow());
        indexMutations.add(m);
      }
    }
    s.close();
    if (inVert == null || outVert == null) {
      return;
    }

    BatchDeleter edgedeleter = null;
    try {
      getEdgeIndexWriter().addMutations(indexMutations);
      globals.getVertexWrapper().deleteEdgeEndpoints(edge);
      globals.getEdgeWrapper().deleteEdge(edge);

      globals.checkedFlush();
      edgedeleter = config.getConnector().createBatchDeleter(config.getVertexTableName(), config.getAuthorizations(), config.getQueryThreads(),
          config.getBatchWriterConfig());
      Mutators.deleteElementRanges(edgedeleter, edge);
    } catch (Exception e) {
      throw new AccumuloGraphException(e);
    } finally {
      if (edgedeleter != null)
        edgedeleter.close();
    }
  }

  @Override
  public Iterable<Edge> getEdges() {
    return globals.getEdgeWrapper().getEdges();
  }

  @Override
  public Iterable<Edge> getEdges(String key, Object value) {
    AccumuloGraphUtils.nullCheckProperty(key, value);
    if (key.equalsIgnoreCase("label")) {
      key = Constants.LABEL;
    }

    if (config.getAutoIndex() || getIndexedKeys(Edge.class).contains(key)) {
      // Use the index
      Scanner s = getEdgeIndexScanner();
      byte[] val = AccumuloByteSerializer.serialize(value);
      Text tVal = new Text(val);
      s.setRange(new Range(tVal, tVal));
      s.fetchColumnFamily(new Text(key));

      return new ScannerIterable<Edge>(s) {
        @Override
        public Edge next(PeekingIterator<Entry<Key,Value>> iterator) {
          Entry<Key,Value> kv = iterator.next();

          Edge e = globals.getCaches().retrieve(kv.getKey().getColumnQualifier().toString(), Edge.class);
          e = (e == null ? new AccumuloEdge(globals, kv.getKey().getColumnQualifier().toString()) : e);

          ((AccumuloElement) e).cacheProperty(kv.getKey().getColumnFamily().toString(),
              AccumuloByteSerializer.deserialize(kv.getKey().getRow().getBytes()));
          globals.getCaches().cache(e, Edge.class);
          return e;
        }
      };
    } else {
      return globals.getEdgeWrapper().getEdges(key, value);
    }
  }

  // TODO Eventually
  @Override
  public GraphQuery query() {
    return new DefaultGraphQuery(this);
  }

  @Override
  public void shutdown() {
    try {
      globals.getMtbw().close();
      globals.getVertexWrapper().close();
      globals.getEdgeWrapper().close();
    } catch (MutationsRejectedException e) {
      e.printStackTrace();
    }
    globals.getCaches().clear(Vertex.class);
    globals.getCaches().clear(Edge.class);
  }

  // public methods not defined by Graph interface, but potentially useful for
  // applications that know they are using an AccumuloGraph
  public void clear() {
    shutdown();

    try {
      TableOperations to;
      to = config.getConnector().tableOperations();
      Iterable<Index<? extends Element>> it = this.getIndices();
      Iterator<Index<? extends Element>> iter = it.iterator();
      while (iter.hasNext()) {
        AccumuloIndex<?> in = (AccumuloIndex<?>) iter.next();
        to.delete(in.tableName);
      }

      for (String t : config.getTableNames()) {
        if (to.exists(t)) {
          to.delete(t);
          to.create(t);
          SortedSet<Text> splits = config.getSplits();
          if (splits != null) {
            to.addSplits(t, splits);
          }
        }
      }
      setupWriters();
    } catch (Exception e) {
      throw new AccumuloGraphException(e);
    }

  }

  public void flush() {
    try {
      globals.getMtbw().flush();
    } catch (MutationsRejectedException e) {
      throw new AccumuloGraphException(e);
    }
  }

  /**
   * @deprecated Move this somewhere appropriate
   */
  /*
  @Deprecated
  void checkedFlush() {
    if (config.getAutoFlush()) {
      flush();
    }
  }
  */

  // methods used by AccumuloElement, AccumuloVertex, AccumuloEdge to interact
  // with the backing Accumulo data store...

  private BatchWriter getIndexBatchWriter(Class<? extends Element> type) {
    if (type.equals(Edge.class))
      return getEdgeIndexWriter();
    return getVertexIndexWriter();
  }

  /*
  private void nullCheckProperty(String key, Object val) {
    if (key == null) {
      throw ExceptionFactory.propertyKeyCanNotBeNull();
    } else if (val == null) {
      throw ExceptionFactory.propertyValueCanNotBeNull();
    } else if (key.trim().equals(StringFactory.EMPTY_STRING)) {
      throw ExceptionFactory.propertyKeyCanNotBeEmpty();
    }
  }

  // internal methods used by this class

  private void checkProperty(String key, Object val) {
    nullCheckProperty(key, val);
    if (key.equals(StringFactory.ID)) {
      throw ExceptionFactory.propertyKeyIdIsReserved();
    } else if (key.equals(StringFactory.LABEL)) {
      throw ExceptionFactory.propertyKeyLabelIsReservedForEdges();
    } else if (val == null) {
      throw ExceptionFactory.propertyValueCanNotBeNull();
    }
  }
  */

  @Override
  public String toString() {
    return AccumuloGraphConfiguration.ACCUMULO_GRAPH_CLASS.getSimpleName().toLowerCase();
  }

  @SuppressWarnings("rawtypes")
  @Override
  public <T extends Element> Index<T> createIndex(String indexName,
      Class<T> indexClass, Parameter... indexParameters) {
    if (indexClass == null) {
      throw ExceptionFactory.classForElementCannotBeNull();
    }
    if (config.getIndexableGraphDisabled())
      throw new UnsupportedOperationException("IndexableGraph is disabled via the configuration");

    Scanner s = getMetadataScanner();
    try {
      s.setRange(new Range(indexName, indexName));
      if (s.iterator().hasNext())
        throw ExceptionFactory.indexAlreadyExists(indexName);

      globals.getIndexMetadataWrapper().writeIndexMetadataEntry(indexName, indexClass);

      return new AccumuloIndex<T>(indexClass, globals, indexName);
    } finally {
      s.close();
    }
  }

  @Override
  public <T extends Element> Index<T> getIndex(String indexName, Class<T> indexClass) {
    if (indexClass == null) {
      throw ExceptionFactory.classForElementCannotBeNull();
    }
    if (config.getIndexableGraphDisabled())
      throw new UnsupportedOperationException("IndexableGraph is disabled via the configuration");

    Scanner scan = getScanner(config.getIndexNamesTableName());
    try {
      scan.setRange(new Range(indexName, indexName));
      Iterator<Entry<Key,Value>> iter = scan.iterator();

      while (iter.hasNext()) {
        Key k = iter.next().getKey();
        if (k.getColumnFamily().toString().equals(indexClass.getSimpleName())) {
          return new AccumuloIndex<T>(indexClass, globals, indexName);
        } else {
          throw ExceptionFactory.indexDoesNotSupportClass(indexName, indexClass);
        }
      }
      return null;
    } finally {
      scan.close();
    }
  }

  @Override
  public Iterable<Index<? extends Element>> getIndices() {
    if (config.getIndexableGraphDisabled())
      throw new UnsupportedOperationException("IndexableGraph is disabled via the configuration");
    List<Index<? extends Element>> toRet = new ArrayList<Index<? extends Element>>();
    Scanner scan = getScanner(config.getIndexNamesTableName());
    try {
      Iterator<Entry<Key,Value>> iter = scan.iterator();

      while (iter.hasNext()) {
        Key k = iter.next().getKey();
        toRet.add(new AccumuloIndex(getClass(k.getColumnFamily().toString()),
            globals, k.getRow().toString()));
      }
      return toRet;
    } catch (Exception e) {
      throw new AccumuloGraphException(e);
    } finally {
      scan.close();
    }
  }

  private Class<? extends Element> getClass(String e) {
    if (e.equals("Vertex")) {
      return Vertex.class;
    }
    return Edge.class;
  }

  @Override
  public void dropIndex(String indexName) {
    if (config.getIndexableGraphDisabled())
      throw new UnsupportedOperationException("IndexableGraph is disabled via the configuration");

    for (Index<? extends Element> index : getIndices()) {
      if (index.getIndexName().equals(indexName)) {
        globals.getIndexMetadataWrapper().clearIndexMetadataEntry(indexName, index.getIndexClass());

        try {
          globals.getConfig().getConnector().tableOperations().delete(globals.getConfig()
              .getNamedIndexTableName(indexName));
        } catch (Exception e) {
          throw new AccumuloGraphException(e);
        }

        return;
      }
    }

    throw new AccumuloGraphException("Index does not exist: "+indexName);
  }

  @Override
  public <T extends Element> void dropKeyIndex(String key, Class<T> elementClass) {
    if (elementClass == null) {
      throw ExceptionFactory.classForElementCannotBeNull();
    }

    globals.getKeyMetadataWrapper().clearKeyMetadataEntry(key, elementClass);

    String table = null;
    if (elementClass.equals(Vertex.class)) {
      table = config.getVertexKeyIndexTableName();
    } else {
      table = config.getEdgeKeyIndexTableName();
    }
    BatchDeleter bd = null;
    try {
      bd = config.getConnector().createBatchDeleter(table, config.getAuthorizations(), config.getMaxWriteThreads(), config.getBatchWriterConfig());
      bd.setRanges(Collections.singleton(new Range()));
      bd.fetchColumnFamily(new Text(key));
      bd.delete();
    } catch (Exception e) {
      throw new AccumuloGraphException(e);
    } finally {
      if (bd != null)
        bd.close();
    }
    globals.checkedFlush();
  }

  @SuppressWarnings("rawtypes")
  @Override
  public <T extends Element> void createKeyIndex(String key,
      Class<T> elementClass, Parameter... indexParameters) {
    if (elementClass == null) {
      throw ExceptionFactory.classForElementCannotBeNull();
    }

    globals.getKeyMetadataWrapper().writeKeyMetadataEntry(key, elementClass);
    globals.checkedFlush();

    // Re Index Graph
    BatchScanner scan = getElementBatchScanner(elementClass);
    try {
      scan.setRanges(Collections.singleton(new Range()));
      scan.fetchColumnFamily(new Text(key));
      Iterator<Entry<Key,Value>> iter = scan.iterator();

      BatchWriter bw = getIndexBatchWriter(elementClass);
      while (iter.hasNext()) {
        Entry<Key,Value> entry = iter.next();
        Key k = entry.getKey();
        Value v = entry.getValue();
        Mutation mu = new Mutation(v.get());
        mu.put(k.getColumnFamily().getBytes(), k.getRow().getBytes(), Constants.EMPTY);
        try {
          bw.addMutation(mu);
        } catch (MutationsRejectedException e) {
          // TODO handle this better.
          throw new AccumuloGraphException(e);
        }
      }
    } finally {
      scan.close();
    }
    globals.checkedFlush();
  }

  @Override
  public <T extends Element> Set<String> getIndexedKeys(Class<T> elementClass) {
    return globals.getKeyMetadataWrapper().getIndexedKeys(elementClass);
  }

  public boolean isEmpty() {
    for (String t : config.getTableNames()) {
      if (getScanner(t).iterator().hasNext()) {
        return false;
      }
    }

    return true;
  }
}
