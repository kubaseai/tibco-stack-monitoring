package EMSJava;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.servlet.Servlet;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;

import com.tibco.tibjms.admin.ConnectionInfo;
import com.tibco.tibjms.admin.ConsumerInfo;
import com.tibco.tibjms.admin.ProducerInfo;
import com.tibco.tibjms.admin.QueueInfo;
import com.tibco.tibjms.admin.ServerInfo;
import com.tibco.tibjms.admin.TibjmsAdmin;
import com.tibco.tibjms.admin.TopicInfo;

@SuppressWarnings("deprecation")
public class EMSJavainvoke{

	protected static volatile long now = System.currentTimeMillis();
	private static ExecutorService es = null;
	private final static ConcurrentLinkedQueue<String> result = new ConcurrentLinkedQueue<>();
	private final static ConcurrentLinkedQueue<String> errors = new ConcurrentLinkedQueue<>();	
	private static EMSJavainvoke INSTANCE = null;

	protected int threadPoolCount = 24;
	protected String connectionStrings = "admin=@localhost:7222";
	
	private static synchronized ExecutorService getExecutorService(int threadCount) {
		if (es==null)
			es = Executors.newFixedThreadPool(threadCount);
		return es;
	}
	
	private final static class ConnectionParamsHolder {
		public String url;
		public String user;
		public String pass;
		
		public static List<ConnectionParamsHolder> fromString(String connString) throws Exception {
			String header = "["+ConnectionParamsHolder.class.getName()+"] ";
			LinkedList<ConnectionParamsHolder> list = new LinkedList<>();
			for (String s : connString.split("\\;")) {
				String[] upHost = s.split("\\@");
				ConnectionParamsHolder ci = new ConnectionParamsHolder();
				ci.url = upHost[1];
				String[] up = upHost[0].split(upHost[0].contains("=") ? "\\=" : "\\:");
				ci.user = up[0];
				ci.pass = up.length > 1 ? up[1] : null;	
				if (ci.pass!=null && ci.pass.startsWith("$")) {
					ci.pass = com.tibco.pe.plugin.PluginProperties.getProperty("tibco.clientVar."+ci.pass.substring(1));
				}			
				list.add(ci);
				System.out.println(header + " connection "+ci);
			}
			return list;
		}
		
		public String toString() {
			return user+"@"+url;
		}
	}
	
	private final static String exceptionToString(Throwable exc) {
		StringWriter sw = new StringWriter();
    		PrintWriter pw = new PrintWriter(sw);
    		pw.append(exc.getMessage()).append(" -> ");
    		exc.printStackTrace(pw);
    		pw.flush();
    		sw.flush();
    		return sw.toString();
	}	
	
	public final static double roundDouble(double d, int places) {
		BigDecimal bd = new BigDecimal(d);
	    	bd = bd.setScale(places, RoundingMode.HALF_UP);
	    	return bd.doubleValue();
	}
	
	public static class EmsStatsExtractor implements Runnable {
		
		private Method m = null;
		private CountDownLatch sync = null;
		private TibjmsAdmin admin = null;
		private String node = null;
		
		public EmsStatsExtractor(Method m, TibjmsAdmin admin, CountDownLatch sync, String node) {
			this.m = m;
			this.sync = sync;
			this.admin = admin;
			this.node = node;
		}
		public String getName() {
			return m.getName();
		}
		
		private boolean isInfoUseful(Object data) {
			String clazz = data.getClass().getName();
			if (clazz.contains("ConnectionFactoryInfo") || clazz.contains(".GroupInfo") || clazz.contains(".UserInfo") || clazz.contains("VersionInfo"))
				return false;
			if (clazz.endsWith("Info"))
				return true;
			String info = data.toString();
			if (info.length()==0 || Character.isDigit(info.charAt(0)) || info.startsWith("$"))
				return false;						
			return false;
		}
		
		public void run() {
			try {
				Object res = m.invoke(admin, new Object[] {});
				LinkedList<Object> toProcess = new LinkedList<>();
				toProcess.add(res);
				while (!toProcess.isEmpty()) {
					Object head = toProcess.remove();
					try {
						Object[] tab = (Object[])head;
						for (Object obj : tab) {
							toProcess.add(obj);
						}						
					}
					catch (Exception notTable) {
						if (isInfoUseful(head)) {
							String data = prepareData(head, node);
							if (data!=null) {
								System.out.println(data);
								result.add(data);
							}
						}
					}
				}
			}
			catch (Throwable e) {
				if (e.getCause()!=null)
					e = e.getCause();
				errors.add(exceptionToString(e));
			}
			finally {
				sync.countDown();
			}
		}	
	}

	public static class EmsStats {
		String nameOrDesc = "";
		int opTime = 0;
		static String host = null;
		
		static {
			try {
				host = InetAddress.getLocalHost().getCanonicalHostName();
			}
			catch (Exception e) {};
		}
	
		private TreeMap<String, Object> getDataInMap() {
			TreeMap<String,Object> data = new TreeMap<String,Object>();
			data.put("opTime", opTime);
			return data;
		}
		
		public String toPrometheusString() {
			StringBuilder sb = new StringBuilder();
			for (Map.Entry<String,Object> entry : getDataInMap().entrySet()) {
				sb.append("Tibco_"+entry.getKey()).append(" { component=\"").append(nameOrDesc).append("\", host=\"").append(host)
				.append("\" } ").append(entry.getValue()).append(" ").append(now).append("\n");
			}			
			return sb.toString();	
		}
	}		
	
	public void invokeService() {	
		now = System.currentTimeMillis();
		List<Method> list = new LinkedList<>();
		List<TibjmsAdmin> adminList = new LinkedList<>();
		List<ConnectionParamsHolder> cphList = new LinkedList<>();
		
		try {
			cphList.addAll(ConnectionParamsHolder.fromString(connectionStrings));
			for (ConnectionParamsHolder cph : cphList) {
				adminList.add(new TibjmsAdmin(cph.url, cph.user, cph.pass));
			}
			for (Method m : adminList.get(0).getClass().getDeclaredMethods()) {
				if (m./*getParameterCount()*/getParameterTypes().length==0 && m.getName().startsWith("get")) {
					if (!m.getName().endsWith("getJACIInfo") && !m.getName().endsWith("getConfiguration"))
						list.add(m);
				}
			}
			CountDownLatch sync = new CountDownLatch(list.size() * adminList.size());
			es = getExecutorService(threadPoolCount);
			for (final Method m : list) {
				for (int i=0; i < adminList.size(); i++)
					es.execute(new EmsStatsExtractor(m, adminList.get(i), sync, cphList.get(i).url));		
			}
			try {
				int timeout = Integer.parseInt(com.tibco.pe.plugin.PluginProperties.getProperty("tibco.clientVar.queryTimeoutSeconds"));
				sync.await(timeout, TimeUnit.SECONDS);
			}
			catch (Throwable e) {
				errors.add("Timeout missed while waiting for all results: "+e);
			}
			
			StringBuilder sb = new StringBuilder();		
			while (result.size() > 0) {
				String emsStat = result.remove();
				sb.append(emsStat);
			}
			content = sb.toString();
			errorMessages = errors.toString();
			errors.clear();
		}
		catch (Throwable e) {
			e.printStackTrace();
			errors.add(exceptionToString(e));
			errorMessages = errors.toString();
		}
		finally {
			for (TibjmsAdmin admin : adminList) {
				if (admin!=null) {
					try {
						admin.close();
					}
					catch (Exception ex) {}
				}
			}
		}
	}
	
	private final static String getFieldByMethod(String field, Object object) {
		for (String mthName : new String[] { "get"+field, "is"+field } ) {
			try {
				Method m = object.getClass().getDeclaredMethod(mthName, new Class[] {});
				m.setAccessible(true);
				return m.invoke(object, new Object[] {}) + "";
			}
			catch (Exception exc) {}
			try {
				Method m = object.getClass().getMethod(mthName, new Class[] {});
				m.setAccessible(true);
				return m.invoke(object, new Object[] {}) + "";				
			}
			catch (Exception exc) {}
		}
		return null;
	}
	
	private final static String getField(String field, Object object) {
		String fieldName = field.split("\\.")[0];
		Object ret = null;
		Class<?> clazz = object.getClass();
		boolean found = false;
		while (!Object.class.equals(clazz)) {
			try {
				Field f = clazz.getField(fieldName);
				f.setAccessible(true);
				ret = f.get(object);	
				found = true;
			}
			catch (Exception e) {}
			if (ret==null) {
				try {
					Field f = clazz.getDeclaredField(fieldName);
					f.setAccessible(true);
					ret = f.get(object);
					found = true;
				}
				catch (Exception e) {}
			}
			if (ret!=null)
				break;
			clazz = clazz.getSuperclass();
		}
		if (field.indexOf('.')!=-1 && ret!=null) {
			String nextField = field.substring(field.indexOf('.')+1);
			// field.subfield or field.getSubfield <=> field.Subfield
			return Character.isLowerCase(nextField.charAt(0)) ? getField(nextField, ret) : getFieldByMethod(nextField, ret);
		}
		return ret==null ? (found ? "" : null) : ret+"";
	}
	
	public final static String getMetrics(Object data, String node, String[] labels, String... metrics) {
		String[] classTokens = data.getClass().getName().split("\\.");
		String name = classTokens[classTokens.length-1];
		HashMap<String,String> retrievedLabels = new HashMap<>();
		HashMap<String,String> retrievedMetrics = new HashMap<>();
		
		for (String label : labels) {
			String lbl = getFieldByMethod(label, data);
			if (lbl!=null) {
				if (label.contains("Name") && lbl.startsWith("$TMP"))
					return null;
				retrievedLabels.put(label, lbl);
			}
		}
		retrievedLabels.put("node", node);
		for (String metric : metrics) {
			String value = Character.isLowerCase(metric.charAt(0)) ? getField(metric, data) : getFieldByMethod(metric, data);
			if (value!=null) {
				if (value.length()>0)
					retrievedMetrics.put(metric, "true".equals(value) ? "1" : ("false".equals(value) ? "0" : value));
			}
			else
				System.err.println("Ooops, field not found: "+metric+", class: "+data.getClass().getName()+" => "+data);			
		}
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, String> en : retrievedMetrics.entrySet()) {
			String[] tokens = en.getKey().replace("instat.", "in.").replace("outstat.", "out.").replace("details.", "").split("\\.");
			sb.append("Ems").append(name.replace("Info", ""));
			for (String token : tokens) {
				token = token.replace("sent", "Sent").replace("size", "Size").replace("acked", "Acked").replace("num", "Num");
				if (token.length() > 0)
					sb.append(token.substring(0, 1).toUpperCase()).append(token.substring(1));
				else {
					System.out.println("Invalid token: "+en.getKey()+" -> "+token);
				}
			}
			sb.append(" { ");
			for (Map.Entry<String, String> ent : retrievedLabels.entrySet()) {
				sb.append(ent.getKey()).append("=\"").append(ent.getValue()).append("\", ");
			}
			if (labels.length > 0)
				sb.setLength(sb.length()-2);
			sb.append(" } ").append(en.getValue()).append(" ").append(System.currentTimeMillis()).append("\n");
		}
		return sb.toString();
	}
	
	public static String prepareData(Object head, String node) {		
		if (head instanceof ServerInfo) {
			return getMetrics(head, node, new String[] { "URL" }, "DurableCount", "PendingMessageCount", "PendingMessageSize", "ConsumerCount", "State", "OverallSyncProgress", "UpTime", "QueueCount", "TopicCount", "ConnectionCount", "SessionCount", "ProducerCount", "InboundMessageCount", "OutboundMessageCount", "InboundMessageRate", "OutboundMessageRate", "InboundBytesRate", "OutboundBytesRate", "LogFileSize", "SyncDBSize", "AsyncDBSize", "MsgMem", "MsgMemPooled", "DiskReadRate", "DiskWriteRate", "DiskReadOperationsRate", "DiskWriteOperationsRate", "DurableCount", "PendingMessageCount", "PendingMessageSize", "ConsumerCount");
		}
		else if (head instanceof QueueInfo) {
			return getMetrics(head, node, new String[] { "Name" }, "ReceiverCount", "DeliveredMessageCount", "InTransitMessageCount", "PendingMessageCount", "PendingMessageSize", "PendingPersistentMessageCount", "PendingPersistentMessageSize", "ConsumerCount", "instat.ByteRate", "instat.MessageRate", "instat.TotalBytes", "instat.TotalMessages", "outstat.ByteRate", "outstat.MessageRate", "outstat.TotalBytes", "outstat.TotalMessages");
		}
		else if (head instanceof TopicInfo) {
			return getMetrics(head, node, new String[] { "Name" }, "SubscriptionCount", "DurableSubscriptionCount", "SubscriberCount", "DurableCount", "ActiveDurableCount", "PendingMessageCount", "PendingMessageSize", "PendingPersistentMessageCount", "PendingPersistentMessageSize", "ConsumerCount", "instat.ByteRate", "instat.MessageRate", "instat.TotalBytes", "instat.TotalMessages", "outstat.ByteRate", "outstat.MessageRate", "outstat.TotalBytes", "outstat.TotalMessages");
		}
		else if (head instanceof ConnectionInfo) {
			return getMetrics(head, node, new String[] { "ID", "URL", "Address" }, "ConsumerCount", "UncommittedCount", "UncommittedSize", "UpTime", "SessionCount", "ProducerCount", "StartTime", "Started" );
		}
		else if (head instanceof ProducerInfo) {
			return getMetrics(head, node, new String[] { "ID", "Username", "DestinationName" }, "stat.ByteRate", "stat.MessageRate", "stat.TotalBytes", "stat.TotalMessages", "CreateTime" );
		}
		else if (head instanceof ConsumerInfo) {		 
			return getMetrics(head, node, new String[] { "ID", "Username", "DestinationName" }, "PendingMessageCount", "PendingMessageSize", "CreateTime", "details.predlv", "details.sentnum", "details.sentsize", "details.lastsent", "details.lastacked", "details.totalsent", "details.totalacked", "stat.ByteRate", "stat.MessageRate", "stat.TotalBytes", "stat.TotalMessages", "Connected" );
		}
		return null;
	}
	
	private void configureParameter(String name) {
		String header = "["+this.getClass().getName()+"] ";
		String valueStr = com.tibco.pe.plugin.PluginProperties.getProperty("tibco.clientVar."+name);

		if (name.contains("String")) {
			try {
				Field f = this.getClass().getDeclaredField(name);
				if (valueStr.length() > 0)
					f.set(this, valueStr);
				System.out.println(header+"Using "+name+": "+f.get(this));
				return;
			}
			catch (Exception e) {
				System.out.println(header+"Invalid parameter value for "+name+"; exception="+e.getMessage());
			}
		}
		
		try {
			Integer value = Integer.valueOf(com.tibco.pe.plugin.PluginProperties.getProperty("tibco.clientVar."+name));
			Field f = this.getClass().getDeclaredField(name);
			if (value!=-1)
				f.set(this, value);
			System.out.println(header+"Using "+name+": "+f.get(this));
		}
		catch (Exception e) {
			System.out.println(header+"Invalid parameter value for "+name+": "+valueStr+"; exception="+e.getMessage());
		}
	}


	private EMSJavainvoke configure() {
		String options = "connectionStrings threadPoolCount";
		for (String s : options.split("\\ "))
			configureParameter(s);
		return this;
	}
	
	private static synchronized EMSJavainvoke getInstance() {
		if (INSTANCE!=null)
			return INSTANCE;
		return (INSTANCE = new EMSJavainvoke().configure());		
	}
	
/****** START SET/GET METHOD, DO NOT MODIFY *****/
	protected String last = "";
	protected String path = "";
	protected String content = "";
	protected String contentType = "";
	protected String errorMessages = "";
	public String getlast() {
		return last;
	}
	public void setlast(String val) {
		last = val;
	}
	public String getpath() {
		return path;
	}
	public void setpath(String val) {
		path = val;
	}
	public String getcontent() {
		return content;
	}
	public void setcontent(String val) {
		content = val;
	}
	public String getcontentType() {
		return contentType;
	}
	public void setcontentType(String val) {
		contentType = val;
	}
	public String geterrorMessages() {
		return errorMessages;
	}
	public void seterrorMessages(String val) {
		errorMessages = val;
	}
/****** END SET/GET METHOD, DO NOT MODIFY *****/
	public EMSJavainvoke() {
	}
	public void invoke() throws Exception {
/* Available Variables: DO NOT MODIFY
	In  : String last
	In  : String path
	Out : String content
	Out : String contentType
	Out : String errorMessages
* Available Variables: DO NOT MODIFY *****/

if (path==null || path.trim().length()==0 || path.equals("/") || path.equals("/favicon.ico")) {
	contentType = "text/html; charset=UTF-8";
	content = "<html><body><h3>This is TibcoEMSExporter exposing <a href='/metrics?last=true'>metrics</a></h3></body>";
	return;
}

contentType = "text/plain; version=0.0.4";
if ("1".equals(last) || "true".equalsIgnoreCase(last) || "yes".equalsIgnoreCase(last))
		;
else
	getInstance().invokeService();
content = getInstance().content;
errorMessages = getInstance().errorMessages;
}
}

