package org.opentraffic.reporter;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.ListIterator;

import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.processor.Processor;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.apache.kafka.streams.processor.ProcessorSupplier;
import org.apache.kafka.streams.processor.StateStoreSupplier;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.Stores;

//here we just take the incoming message, reformat it and key it while doing so
public class BatchingProcessor implements ProcessorSupplier<String, Point> {
  
  private static final String BATCH_STORE_NAME = "batch";
  public static StateStoreSupplier<?> GetStore() {
    return Stores.create(BATCH_STORE_NAME).withStringKeys().withValues(new Batch.Serder()).inMemory().build();
  }
  
  //TODO: get these magic constants from arguments
  private final long REPORT_TIME = 60000;
  private final int REPORT_COUNT = 10;
  private final int REPORT_DIST = 500;
  private final long SESSION_GAP = 60000;
  private final String url =  "http://localhost:8002/report?";

  public BatchingProcessor(String[] args) {
    //TODO: parse args into private final vars above
  }
  
  @Override
  public Processor<String, Point> get() {
    return new Processor<String, Point>() {
      private ProcessorContext context;
      private KeyValueStore<String, Batch> store;
      private LinkedList<Pair<Long, String> > time_to_key;
      private HashMap<String, ListIterator<Pair<Long, String> > > key_to_time_iter;
  
      @SuppressWarnings("unchecked")
      @Override
      public void init(ProcessorContext context) {
        this.context = context;
        this.store = (KeyValueStore<String, Batch>) context.getStateStore(BATCH_STORE_NAME);
        this.time_to_key = new LinkedList<Pair<Long, String> >();
        this.key_to_time_iter = new HashMap<String, ListIterator<Pair<Long, String> > >();
      }
  
      @Override
      public void process(String key, Point point) {
        //clean up stale keys
        clean(key);
        
        //get this batch out of storage and update it
        Batch batch = store.delete(key);
        if(batch == null)
          batch = new Batch(point);
        else
          batch.update(point);
        
        //see if it needs reported on
        report(key, batch);
        
        //put it back if it has something
        if(batch.points.size() > 0)
          this.store.put(key, batch);
        //remove all traces if not
        else {
          ListIterator<Pair<Long, String> > iter = key_to_time_iter.get(key);
          time_to_key.remove(iter);
        }
        
        //move on
        context.commit();
      }
      
      private void report(String key, Batch batch) {
        //if it meets the requirements lets act on it
        if(batch.traveled > REPORT_DIST * REPORT_DIST && batch.points.size() > REPORT_COUNT && batch.elapsed > REPORT_TIME) {
          String response = batch.report(key, url);
          //for now we'll just forward the response on in case we want something downstream
          context.forward(key, response);
        }
      }
      
      private void clean(String key) {
        //TODO: this might be too blunt, processing delay could cause us to evict stuff from the store
        //below we use the context's timestamp to get the time of the current record we are processing
        //if processing delay occurs this timestamp will deviate from the current time but will still be
        //relative to the time stored in our oldest first list. we may also want to move this potential
        //bottleneck to the punctuate function and do it on a regular interval although the timestamp
        //semantics do change in that configuration

        //go through the keys in stalest first order keys
        while(time_to_key.size() > 0 && context.timestamp() - time_to_key.getFirst().first > SESSION_GAP) {
          //this fogey hasn't been producing much, off to the glue factory
          Pair<Long, String> time_key = time_to_key.pop();
          Batch batch = this.store.get(time_key.second);
          //TODO: dont actually report here, instead insert into a queue that a thread can drain asynchronously
          batch.report(time_key.second, url);
          key_to_time_iter.remove(time_key.second);          
        }
        
        //mark this key as recently having an update
        ListIterator<Pair<Long, String> > iter = key_to_time_iter.get(key);
        if(iter != null)
          time_to_key.remove(iter);
        time_to_key.add(new Pair<Long, String>(context.timestamp(), key));
        iter = time_to_key.listIterator(time_to_key.size() - 1); //O(1)
        key_to_time_iter.put(key,  iter);
      }
  
      @Override
      public void punctuate(long timestamp) {
        //we dont really want to do anything on a regular interval
      }
  
      @Override
      public void close() {
        //take care of the rest of the stuff thats hanging around
        KeyValueIterator<String, Batch> iter = store.all();
        while(iter.hasNext()) {
          KeyValue<String, Batch> kv = iter.next();
          report(kv.key, kv.value);
        }
        iter.close();
        //clean up
        store.flush();
      }
    };
  }
}
