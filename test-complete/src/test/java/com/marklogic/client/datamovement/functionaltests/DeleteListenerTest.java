package com.marklogic.client.datamovement.functionaltests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marklogic.client.DatabaseClient;
import com.marklogic.client.DatabaseClientFactory;
import com.marklogic.client.DatabaseClientFactory.Authentication;
import com.marklogic.client.datamovement.DataMovementManager;
import com.marklogic.client.datamovement.DeleteListener;
import com.marklogic.client.datamovement.JobTicket;
import com.marklogic.client.datamovement.QueryBatcher;
import com.marklogic.client.datamovement.WriteBatcher;
import com.marklogic.client.datamovement.functionaltests.util.DmsdkJavaClientREST;
import com.marklogic.client.document.DocumentPage;
import com.marklogic.client.document.DocumentRecord;
import com.marklogic.client.impl.DatabaseClientImpl;
import com.marklogic.client.io.DocumentMetadataHandle;
import com.marklogic.client.io.FileHandle;
import com.marklogic.client.io.Format;
import com.marklogic.client.io.JacksonHandle;
import com.marklogic.client.io.StringHandle;
import com.marklogic.client.query.StructuredQueryBuilder;

public class DeleteListenerTest extends  DmsdkJavaClientREST{
	
	private static String dbName = "DeleteListener";
	private static DataMovementManager dmManager = DataMovementManager.newInstance();
	private static final String TEST_DIR_PREFIX = "/WriteHostBatcher-testdata/";
	
	private static DatabaseClient dbClient;
	private static String host = "localhost";
	private static String user = "admin";
	private static int port = 8000;
	private static String password = "admin";
	private static String server = "App-Services";
	private static JsonNode clusterInfo;
	
	private static JacksonHandle jacksonHandle;
	private static StringHandle stringHandle;
	private static FileHandle fileHandle;
	
	private static DocumentMetadataHandle meta;
	
	private static String stringTriple;
	private static File fileJson;
	private static JsonNode jsonNode;
	private static final String query1 = "fn:count(fn:doc())";
	private static String[] hostNames ;
	
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		hostNames = getHosts();	    
		createDB(dbName);
		Thread.currentThread().sleep(500L);
		int count = 1;
		for ( String forestHost : hostNames ) {
			createForestonHost(dbName+"-"+count,dbName,forestHost);
		    count ++;
			Thread.currentThread().sleep(500L);
		}
			
		associateRESTServerWithDB(server,dbName);
		
		dbClient = DatabaseClientFactory.newClient(host, port, user, password, Authentication.DIGEST);
		dmManager.withClient(dbClient);
		
		clusterInfo = ((DatabaseClientImpl) dbClient).getServices()
			      .getResource(null, "forestinfo", null, null, new JacksonHandle())
			      .get();
		
		//JacksonHandle
		jsonNode = new ObjectMapper().readTree("{\"k1\":\"v1\"}");
		jacksonHandle = new JacksonHandle();
		jacksonHandle.set(jsonNode);
		
		meta = new DocumentMetadataHandle().withCollections("DeleteListener");
		
		
		//StringHandle
		stringTriple = "<abc>xml</abc>";
		stringHandle = new StringHandle(stringTriple);
		stringHandle.setFormat(Format.XML);
		
		
		// FileHandle
		fileJson = FileUtils.toFile(WriteHostBatcherTest.class.getResource(TEST_DIR_PREFIX+"dir.json"));
		fileHandle = new FileHandle(fileJson);
		fileHandle.setFormat(Format.JSON);
	}
	
	
	@AfterClass
	public static void tearDownAfterClass() throws Exception {
		associateRESTServerWithDB(server,"Documents");
		for (int i =0 ; i < clusterInfo.size(); i++){
			System.out.println(dbName+"-"+(i+1));
			detachForest(dbName, dbName+"-"+(i+1));
			deleteForest(dbName+"-"+(i+1));
		}
		
		deleteDB(dbName);
	}

	

	@Before
	public void setUp() throws Exception {
		Thread.currentThread().sleep(1000L);
		WriteBatcher ihb2 =  dmManager.newWriteBatcher();
		ihb2.withBatchSize(27).withThreadCount(10);
		ihb2.onBatchSuccess(
		        (client, batch) -> {
		        	
		        	
		        	}
		        )
		        .onBatchFailure(
		          (client, batch, throwable) -> {
		        	 throwable.printStackTrace();
		          });
		
		dmManager.startJob(ihb2);
		for (int j =0 ;j < 2000; j++){
			String uri ="/local/json-"+ j;
			ihb2.add(uri, meta, jacksonHandle);
		}
	
		ihb2.flushAndWait();
		Assert.assertTrue(dbClient.newServerEval().xquery(query1).eval().next().getNumber().intValue() == 2000);

	}
	
	@After
	public void tearDown() throws Exception {
		clearDB(port);
	}
	
	@Test
	public void massDeleteSingleThread() throws Exception{
		Set<String> uriSet = new HashSet<>();
		
		Assert.assertTrue(uriSet.isEmpty());
		Assert.assertTrue(dbClient.newServerEval().xquery(query1).eval().next().getNumber().intValue() == 2000);
		
	    QueryBatcher queryBatcher = dmManager.newQueryBatcher(
	        new StructuredQueryBuilder().collection("DeleteListener"))
	    .withBatchSize(11)
	    .withThreadCount(1)
	     .onUrisReady((client, batch)->{
	    	 for(String s: batch.getItems()){
	    		uriSet.add(s);
	    		
	    	}
	     })
	    .onQueryFailure((client, throwable)-> {        	
	        	System.out.println("Exceptions thrown from callback onQueryFailure");        	
	            throwable.printStackTrace();
	          	         	
	        });
	 
	    JobTicket ticket = dmManager.startJob(queryBatcher);
		queryBatcher.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
	    dmManager.stopJob(ticket);
	    
	    Thread.currentThread().sleep(2000L);
	    Assert.assertTrue(uriSet.size() == 2000);
	    
	    AtomicInteger successDocs = new AtomicInteger();
	    HashSet<String> uris2 = new HashSet<>();
	    StringBuffer failures2 = new StringBuffer();
	
	    QueryBatcher deleteBatcher = dmManager.newQueryBatcher(uriSet.iterator())
	    .withBatchSize(23)
	    .withThreadCount(1)
	    .onUrisReady(new DeleteListener())
	    .onUrisReady((client, batch) -> successDocs.addAndGet(batch.getItems().length))
	    .onUrisReady((client, batch) -> uris2.addAll(Arrays.asList(batch.getItems())))
	    .onQueryFailure( (client, throwable) -> {
	        throwable.printStackTrace();
	        failures2.append("ERROR:[" + throwable + "]\n");
	      });
	    
	    JobTicket delTicket = dmManager.startJob(deleteBatcher);
	    deleteBatcher.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
	    dmManager.stopJob(delTicket);
	    
	   	if ( failures2.length() > 0 ) fail(failures2.toString());
	    Assert.assertTrue(dbClient.newServerEval().xquery(query1).eval().next().getNumber().intValue() == 0);
	}
	
	@Test
	public void massDeleteMultipleThreads() throws Exception{
		
		HashSet<String> urisList = new HashSet<>();

		QueryBatcher queryBatcher = dmManager.newQueryBatcher(
	        new StructuredQueryBuilder().collection("DeleteListener"))
	    .withBatchSize(11)
	    .withThreadCount(11)
	    .onUrisReady((client, batch)->{
	     	synchronized(this){
	    		urisList.addAll(Arrays.asList(batch.getItems()));
	    	}
	    	
	     })
	  	.onQueryFailure((client, throwable)-> {        	
	        	System.out.println("Exceptions thrown from callback onQueryFailure");        	
	            throwable.printStackTrace();
	          	         	
	        });
	 
	    JobTicket ticket = dmManager.startJob(queryBatcher);
		queryBatcher.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
	    dmManager.stopJob(ticket);
	    
	   
	    Assert.assertTrue(urisList.size() == 2000);
	    
	    AtomicInteger successDocs = new AtomicInteger();
	    HashSet<String> uris2 = new HashSet<>();
	    StringBuffer failures2 = new StringBuffer();
	
	    QueryBatcher deleteBatcher = dmManager.newQueryBatcher(urisList.iterator())
	    .withBatchSize(119)
	    .withThreadCount(11)
	    .onUrisReady(new DeleteListener())
	    .onUrisReady((client, batch) -> successDocs.addAndGet(batch.getItems().length))
	    .onUrisReady((client, batch) -> uris2.addAll(Arrays.asList(batch.getItems())))
	    .onQueryFailure( (client, throwable) -> {
	        throwable.printStackTrace();
	        failures2.append("ERROR:[" + throwable + "]\n");
	      });
	    
	    JobTicket delTicket = dmManager.startJob(deleteBatcher);
	    deleteBatcher.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
	    dmManager.stopJob(delTicket);
	    
	   	if ( failures2.length() > 0 ) fail(failures2.toString());
	    Assert.assertTrue(dbClient.newServerEval().xquery(query1).eval().next().getNumber().intValue() == 0);
	}
	
	@Test
	public void massDeleteConsistentSnapShot() throws Exception{
		Map<String, String> props = new HashMap<String, String>();
 		props.put("merge-timestamp","-6000000000");
 		changeProperty(props,"/manage/v2/databases/"+dbName+"/properties");
 		Thread.currentThread().sleep(5000L);
 		
 		QueryBatcher queryBatcher = dmManager.newQueryBatcher(
	        new StructuredQueryBuilder().collection("DeleteListener"))
	    .withBatchSize(7)
	    .withConsistentSnapshot()
	    .withThreadCount(5)
	    .onUrisReady(new DeleteListener())
	  	.onQueryFailure((client, throwable)-> {        	
	        	System.out.println("Exceptions thrown from callback onQueryFailure");        	
	            throwable.printStackTrace();
	          	         	
	        });
	 
	    JobTicket ticket = dmManager.startJob(queryBatcher);
		queryBatcher.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
	    dmManager.stopJob(ticket);
	    

		props.put("merge-timestamp","0");
 		changeProperty(props,"/manage/v2/databases/"+dbName+"/properties");
	//   	if ( failures2.length() > 0 ) fail(failures2.toString());
	    assertEquals(0, dbClient.newServerEval().xquery(query1).eval().next().getNumber().intValue());
	}
		
	@Test
	public void deleteNonExistentDoc() throws Exception{
		
		HashSet<String> urisList = new HashSet<>();
		//add a non existent doc uri
		urisList.add("/abc/nonexistent");
	    
	    AtomicInteger successDocs = new AtomicInteger();
	    HashSet<String> uris2 = new HashSet<>();
	    StringBuffer failures2 = new StringBuffer();
	    
	
	    QueryBatcher deleteBatcher = dmManager.newQueryBatcher(urisList.iterator())
	    .withBatchSize(11)
	    .withThreadCount(11)
	    .onUrisReady(new DeleteListener())
	    .onUrisReady((client, batch) -> successDocs.addAndGet(batch.getItems().length))
	    .onUrisReady((client, batch) -> uris2.addAll(Arrays.asList(batch.getItems())))
	    .onQueryFailure( (client, throwable) -> {
	        throwable.printStackTrace();
	        failures2.append("ERROR:[" + throwable + "]\n");
	      });
	    
	    JobTicket delTicket = dmManager.startJob(deleteBatcher);
	    deleteBatcher.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
	    dmManager.stopJob(delTicket);
	    
	   	if ( failures2.length() > 0 ) fail(failures2.toString());
	    Assert.assertTrue(dbClient.newServerEval().xquery(query1).eval().next().getNumber().intValue() == 2000);
	}
	
	// ISSUE 94
	@Test
	public void deleteServerFile() throws Exception{
		
		Map<String, String> props = new HashMap<String, String>();
 		props.put("merge-timestamp","-6000000000");
 		changeProperty(props,"/manage/v2/databases/"+dbName+"/properties");
 		Thread.currentThread().sleep(5000L);
 		
	    class MyRunnable implements Runnable {
	    	@Override
	       	public void run() {
	    		for (int j =1999 ;j >=200 ;j--){
	    			dbClient.newDocumentManager().delete("/local/json-"+ j);
	    		}
	        } 
	    }
	    Thread t1;
       	t1 = new Thread(new MyRunnable());
       	
    	HashSet<String> urisList = new HashSet<>();
				
    	QueryBatcher queryBatcher = dmManager.newQueryBatcher(
	        new StructuredQueryBuilder().collection("DeleteListener"))
	    .withBatchSize(11)
	    .withThreadCount(4)
	    .withConsistentSnapshot()
	    .onUrisReady((client, batch)->{
	    	for(String s: batch.getItems()){
	    		urisList.add(s);
	    	}
	     })
	   	.onQueryFailure((client, throwable)-> {        	
	        	System.out.println("Exceptions thrown from callback onQueryFailure");        	
	            throwable.printStackTrace();
	          	         	
	        });
	    
		t1.start();
		JobTicket ticket = dmManager.startJob(queryBatcher);
		
		queryBatcher.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
		t1.join();
	    dmManager.stopJob(ticket);
	    
	    System.out.println("URI's size "+urisList.size());
	    AtomicInteger successDocs = new AtomicInteger();
	    HashSet<String> uris2 = new HashSet<>();
	    StringBuffer failures2 = new StringBuffer();
	
	    QueryBatcher deleteBatcher = dmManager.newQueryBatcher(urisList.iterator())
	    .withBatchSize(13)
	    .onUrisReady(new DeleteListener())
	    .onUrisReady((client, batch) -> successDocs.addAndGet(batch.getItems().length))
	    .onUrisReady((client, batch) -> uris2.addAll(Arrays.asList(batch.getItems())))
	    .onQueryFailure( (client, throwable) -> {
	        throwable.printStackTrace();
	        failures2.append("ERROR:[" + throwable + "]\n");
	      });
	    
	    JobTicket delTicket = dmManager.startJob(deleteBatcher);
	    deleteBatcher.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
	    dmManager.stopJob(delTicket);
	    
	   	if ( failures2.length() > 0 ) fail(failures2.toString());
		
		assertEquals( "There should be 0 documents in t++he db",
			      0, dbClient.newServerEval().xquery(query1).eval().next().getNumber().intValue() );
		
		DocumentPage page = dbClient.newDocumentManager().read("/local/json-1998");
		JacksonHandle dh = new JacksonHandle();
		while(page.hasNext()){
			DocumentRecord rec = page.next();
			rec.getContent(dh);
			System.out.println("Results are: "+dh.get().get("k1").asText());
			
		}
		
		props.put("merge-timestamp","0");
 		changeProperty(props,"/manage/v2/databases/"+dbName+"/properties");
	 }
	
	@Test
	public void deleteEmptyIterator() throws Exception{
		
		HashSet<String> urisList = new HashSet<>();
			    
	    AtomicInteger successDocs = new AtomicInteger();
	    StringBuffer failures2 = new StringBuffer();
	    
	
	    QueryBatcher deleteBatcher = dmManager.newQueryBatcher(urisList.iterator())
	    .withBatchSize(11)
	    .withThreadCount(11)
	    .onUrisReady(new DeleteListener())
	    .onUrisReady((client, batch) -> successDocs.addAndGet(batch.getItems().length))
	    .onQueryFailure( (client, throwable) -> {
	        throwable.printStackTrace();
	        failures2.append("ERROR:[" + throwable + "]\n");
	      });
	    
	    JobTicket delTicket = dmManager.startJob(deleteBatcher);
	    deleteBatcher.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
	    dmManager.stopJob(delTicket);
	    
	   	if ( failures2.length() > 0 ) fail(failures2.toString());
	   	Assert.assertTrue(successDocs.intValue()==0);
	    Assert.assertTrue(dbClient.newServerEval().xquery(query1).eval().next().getNumber().intValue() == 2000);
	}
	
	@Test
	public void testModifyIteratorAdd() throws Exception{
		
		HashSet<String> urisList = new HashSet<>();
			    
	    AtomicInteger successDocs = new AtomicInteger();
	    StringBuffer failures2 = new StringBuffer();
	    
	    class MyRunnable1 implements Runnable {
	       	  
	       	  @Override
	       	  public void run() {
	         		
	           		for (int j =0 ;j < 1000; j++){
	    				String uri ="/local/json-"+ j;
	    				urisList.add(uri);
	    				if(j%100 ==0){
	    					try {
								Thread.currentThread().sleep(50L);
							} catch (InterruptedException e) {
								// TODO Auto-generated catch block
								e.printStackTrace();
							}
	    					
	    				}
	    			}
	          }  
	    } 
	    
	    class MyRunnable2 implements Runnable {
	       	  
	       	  @Override
	       	  public void run() {
	         		
	           		for (int j =1000 ;j < 2000; j++){
	    				String uri ="/local/json-"+ j;
	    				urisList.add(uri);
	    				if(j%100 ==0){
	    					try {
								Thread.currentThread().sleep(30L);
							} catch (InterruptedException e) {
								// TODO Auto-generated catch block
								e.printStackTrace();
							}
	    					
	    				}
	    			}
	           }  
	           		
	    }
	    Thread t1,t2;
	    t1 = new Thread(new MyRunnable1());
	    t1.setName("Addition Thread 1");
	    
	    t2 = new Thread(new MyRunnable2());
	    t1.setName("Addition Thread 2");
	    
	
	    t1.start();
	    Thread.currentThread().sleep(10L);
	       
	    QueryBatcher deleteBatcher = dmManager.newQueryBatcher(urisList.iterator())
	    .onUrisReady(new DeleteListener())
	    .onUrisReady((client, batch) ->{
	    	System.out.println("Items in batch "+batch.getItems().length);
	    	successDocs.addAndGet(batch.getItems().length);}
	    )
	    .onQueryFailure( (client, throwable) -> {
	    	System.out.println("Query Failed");
	        throwable.printStackTrace();
	        failures2.append("ERROR:[" + throwable + "]\n");
	      })
	    .withBatchSize(9)
	    .withThreadCount(3);
	   
	    t2.start();
	    Thread.currentThread().sleep(30L);
	    
	        
	    JobTicket delTicket = null;
	    try{
	    	 System.out.println("Job starting: urisList Size is "+urisList.size());
	    	 delTicket = dmManager.startJob(deleteBatcher);
	    	 deleteBatcher.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
	 	     System.out.println("Succes Docs "+successDocs.intValue());
		     System.out.println("DB size: "+dbClient.newServerEval().xquery(query1).eval().next().getNumber().intValue());
	    	 Assert.assertFalse("Exception was not thrown, when it should have been", 1<2);
	    }
	    catch(Exception e){
	    	Assert.assertTrue(e instanceof ConcurrentModificationException);
	    }
	   
	    
	    dmManager.stopJob(delTicket);
	    t1.join();
	    t2.join();
	}
	
	@Test
	public void testModifyIteratorRemove() throws Exception{
		
		HashSet<String> urisList = new HashSet<>();
		String uris[] = new String[2000];
		for(int i =0; i<2000; i++){
			String uri = new String("/local/json-"+ i);
			uris[i] = uri;
			urisList.add(uri);
		}
			    
	    AtomicInteger successDocs = new AtomicInteger();
	    StringBuffer failures2 = new StringBuffer();
	  
	    
	    class MyRunnable1 implements Runnable {
	       	  
	       	  @Override
	       	  public void run() {
	         		
	           		for (int j =1999 ;j > 1000; j--){
	           			urisList.remove(uris[j]);
	    				if(j%100 ==0){
	    					try {
								Thread.currentThread().sleep(100L);
							} catch (InterruptedException e) {
								// TODO Auto-generated catch block
								e.printStackTrace();
							}
	    					
	    				}
	    			}
	          }  
	    } 
	    

	    Thread t1;
	    t1 = new Thread(new MyRunnable1());
	    t1.setName("Deletion Thread");
	    
	   
	          
	    QueryBatcher deleteBatcher = dmManager.newQueryBatcher(urisList.iterator())
	    .onUrisReady(new DeleteListener())
	    .onUrisReady((client, batch) ->{
	    	System.out.println("Items in batch "+batch.getItems().length);
	    	successDocs.addAndGet(batch.getItems().length);}
	    )
	    .onQueryFailure( (client, throwable) -> {
	    	System.out.println("Query Failed");
	        throwable.printStackTrace();
	        failures2.append("ERROR:[" + throwable + "]\n");
	      })
	    .withBatchSize(7)
	    .withThreadCount(3);
	  
	  
	    t1.start();
	    Thread.currentThread().sleep(30L);
	    System.out.println("urisList Size is "+urisList.size());
	    JobTicket delTicket = null;
	    try{
	    	 delTicket = dmManager.startJob(deleteBatcher);
	    	 Assert.assertFalse("Exception was not thrown, when it should have been", 1<2);
	    }
	    catch(Exception e){
	    	Assert.assertTrue(e instanceof ConcurrentModificationException);
	    }
	   
	    
	    dmManager.stopJob(delTicket);
	    t1.join();
	   
	}
}