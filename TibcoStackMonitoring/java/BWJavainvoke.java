package BWJava;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javax.management.MBeanServerConnection;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularDataSupport;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

@SuppressWarnings("deprecation")
public class BWJavainvoke{

	protected static Class<?> OperatingSystemMXBeanClass = null;
	protected static Method getProcessCpuTime = null;
	protected static Method getAvailableProcessors = null;
	protected static Class<?> VirtualMachineClass = null;
	protected static Class<?> VirtualMachineDescriptorClass = null;
	protected static Method vmAttach = null;
	protected static Method vmDetach = null;
	protected static Method vmList = null;
	protected static Method getSystemProperties = null;
	protected static Method getAgentProperties = null;
	protected static Method loadAgent = null;
	protected static Method startLocalManagementAgent = null;
	protected static Class<?> VMSupportClass = null;
	protected static volatile long now = System.currentTimeMillis();
	private static ExecutorService es = null;
	private final static ConcurrentLinkedQueue<String> result = new ConcurrentLinkedQueue<>();
	private final static ConcurrentLinkedQueue<String> errors = new ConcurrentLinkedQueue<>();
	private final static ConcurrentHashMap<String, Method> methodCache = new ConcurrentHashMap<>();
	private static BWJavainvoke INSTANCE = null;
	
	protected int threadPoolCount = 24;
	protected long minElapsedWarnThresholdMillis = 30000;
	protected long maxElapsedWarnThresholdMillis = 60000;
	protected int samplesCount = 5;
	protected int probingPeriodMillis = 1000;
	protected int reconnectCount = 2;
	protected int queryTimeoutSeconds = 15;
	protected int zeroOneSignalPeriodTimeMillis = 600000;

	private static synchronized ExecutorService getExecutorService(int threadCount) {
		if (es==null)
			es = Executors.newFixedThreadPool(threadCount);
		return es;
	}
	
	private final static void setRmiProperties() {
		//System.setProperty("sun.rmi.client.logCalls", "true");
		System.setProperty("sun.rmi.dgc.cleanInterval", "30000");
		System.setProperty("sun.rmi.dgc.client.gcInterval", "60000");
		System.setProperty("sun.rmi.transport.connectionTimeout", "5000");
		System.setProperty("sun.rmi.transport.tcp.responseTimeout", "5000");
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
	
	public final static List<String> system(String cmd, int timeout) {
		try {
			ProcessBuilder pb = new ProcessBuilder("bash","-c", cmd);
			pb.redirectErrorStream();
			final Process proc = pb.start();
			final BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()));
			final LinkedBlockingQueue<Object> q = new LinkedBlockingQueue<>();
			final long endTime = System.currentTimeMillis() + timeout;
			new Thread() {
				public void run() {
					try {
						String line = null;
						while ((line=br.readLine())!=null && System.currentTimeMillis() < endTime)
							q.put(line);
						proc.destroy();
						
					}
					catch (Throwable ie) {}
					try {
						q.put(Long.valueOf(0));
					}
					catch (InterruptedException e) {}
				}
			}.start();
			LinkedList<String> lines = new LinkedList<>();
			while (System.currentTimeMillis() < endTime) {
				Object obj = q.poll(100, TimeUnit.MILLISECONDS);
				if (obj!=null && obj instanceof Long)
					break;
				if (obj!=null)
					lines.add(obj.toString());
			}
			return lines;
		}
		catch (Throwable exc) {
			LinkedList<String> lines = new LinkedList<>();
			lines.add(exc.toString());
			return lines;
		}		
	}

	
	public static class VMStatsExtractor implements Runnable {
		private Object vmd = null;
		private CountDownLatch sync = null;
		private BWJavainvoke config = null;
		
		public VMStatsExtractor(Object vmd, CountDownLatch sync, BWJavainvoke config) {
			this.vmd = vmd;
			this.sync = sync;
			this.config = config;
		}
		public String getName() {
			StringTokenizer st = new StringTokenizer(vmd+"", " ");
			st.nextToken();
			return vmd!=null ? "PID|"+st.nextToken()+"|"+st.nextToken() : null;
		}
		
		public void run() {
			try {
				VMStats stats = query(vmd, config);
				if (stats.nameOrDesc.startsWith("TRA|") || stats.nameOrDesc.startsWith("BW") || stats.nameOrDesc.startsWith("OS"))
					result.add(stats.toPrometheusString());
			}
			catch (Throwable e) {
				errors.add("Error while querying JMX for "+vmd+": "+e);
				VMStats vms = new VMStats(false);
				vms.nameOrDesc = getName();
				vms.inaccessible = 1;
				result.add(vms.toPrometheusString());
			}	
			finally {
				sync.countDown();
			}
		}	
	}
	
	public static class BWStats {
		String process = "";
		String activity = "";
		Long createdJobs = null;
		Long completedJobs = null;
		Long abortedJobs = null;
		Long swappedJobs = null;
		Long suspendedJobs = null;
		Long queuedJobs = null;
		Long inFlightJobs = null;
		
		Double avgElapsedTime = null;
		Double avgExecutionTime = null;
		Long minElapsedTime = null;
		Long maxElapsedTime = null;
		Long lastElapsedTime = null;
		Long hasErrors = null;	
		Long runningTime = null;
		
		private TreeMap<String, Object> getDataInMap() {
			TreeMap<String,Object> data = new TreeMap<String,Object>();
			data.put("createdJobs", createdJobs);
			data.put("completedJobs", completedJobs);
			data.put("abortedJobs", abortedJobs);
			data.put("swappedJobs", swappedJobs);
			data.put("suspendedJobs", suspendedJobs);
			data.put("queuedJobs", queuedJobs);
			data.put("inFlightJobs", inFlightJobs);
			data.put("avgElapsedTime", avgElapsedTime);
			data.put("avgExecutionTime", avgExecutionTime);
			data.put("minElapsedTime", minElapsedTime);
			data.put("maxElapsedTime", maxElapsedTime);
			data.put("lastElapsedTime", lastElapsedTime);
			data.put("hasErrors", hasErrors);
			data.put("runningTime", runningTime);
			return data;
		}
		
		private void trimNames() {
			process = (process == null ? "" : process.replace(' ', '-'));
			activity = (activity == null ? "" : activity.replace(' ', '-').replace('|', '^'));			
		}
	
		public String toPrometheusString(String appName) {		
			StringBuilder sb = new StringBuilder();
			boolean isProcess = completedJobs!=null;
			String object = process + "/" + (isProcess ? "*" : "") + activity;
			String type = isProcess ? "process" : "activityNotice";
			for (Map.Entry<String,Object> entry : getDataInMap().entrySet()) {
				if (entry.getValue()!=null)
					sb.append("Bw_"+entry.getKey()).append(" { component=\"").append(appName).append("\", object=\"").append(object)
					.append("\", type=\"").append(type)	.append("\", host=\"").append(VMStats.host).append("\" } ")
					.append(entry.getValue()).append(" ").append(now).append("\n");
			}		
			return sb.toString();
		}
		
		public final static void getZeroOneSignalPrometheusString(StringBuilder sb, BWJavainvoke config) {
			long period = config.zeroOneSignalPeriodTimeMillis;
			int value = (System.currentTimeMillis() % period) < (period >> 1) ? 0 : 1;
			sb.append("ZeroOneSignal { host=\"").append(VMStats.host).append("\" } ")
				.append(value).append(" ").append(now).append("\n");
		}
	
		public String toJsonString() {
			StringBuilder sb = new StringBuilder(String.format("\"bwstat\": {\"activity\":\"%s\"",process+"/"+activity));
			for (Map.Entry<String,Object> entry : getDataInMap().entrySet()) {
				if (entry.getValue()!=null)
					sb.append(",\"").append(entry.getKey()).append("\":\"").append(entry.getValue()).append("\"");
			}
			sb.append("}");
			return sb.toString();
		}
		
		private static Long computeInFlightJobs(BWStats bw) {
			long result = 0L;
			if (bw.createdJobs!=null)
				result = bw.createdJobs;
			if (bw.completedJobs!=null)
				result -= bw.completedJobs;
			if (bw.abortedJobs!=null)
				result -= bw.abortedJobs;
			if (bw.queuedJobs!=null)
				result -= bw.queuedJobs;
			if (bw.suspendedJobs!=null)
				result -= bw.suspendedJobs;
			if (bw.swappedJobs!=null)
				result -= bw.swappedJobs;
			return result;
		}
			
		public static List<BWStats> fromProcessStats(TabularDataSupport processDefinitions) {
			LinkedList<BWStats> list = new LinkedList<>();
			if (processDefinitions!=null) {
				for (Object _row : processDefinitions.values()) {
					BWStats bw = new BWStats();
					CompositeData row = (CompositeData)_row;
					bw.process = (String) row.get("Name");	
					bw.activity = (String) row.get("Starter");
					bw.abortedJobs = (Long) row.get("Aborted");
					bw.createdJobs = (Long) row.get("Created");
					bw.completedJobs = (Long) row.get("Completed");
					bw.suspendedJobs = (Long) row.get("Suspended");
					bw.swappedJobs = (Long) row.get("Swapped");
					bw.queuedJobs = (Long) row.get("Queued");
					bw.inFlightJobs = computeInFlightJobs(bw);
					
					bw.avgElapsedTime = (Long) row.get("AverageElapsed") + 0.0;
					bw.avgExecutionTime = (Long) row.get("AverageExecution") + 0.0;
					bw.minElapsedTime = (Long) row.get("MinElapsed");
					bw.maxElapsedTime = (Long) row.get("MaxElapsed");
					bw.lastElapsedTime = (Long) row.get("MostRecentElapsedTime");
					bw.hasErrors = Long.valueOf(0L).equals(bw.abortedJobs) ? null : bw.abortedJobs;
					
					bw.trimNames();
					if (bw.isRelevant(INSTANCE))
						list.add(bw);
				}
			}
			return list;
		}
		
		public static List<BWStats> fromActivityStats(TabularDataSupport activities, BWJavainvoke config) {
			LinkedList<BWStats> list = new LinkedList<>();
			if (activities!=null) {
				for (Object _row : activities.values()) {
					CompositeData row = (CompositeData)_row;
					BWStats bw = new BWStats();
					String lastReturnCode = (String) row.get("LastReturnCode");
					bw.process = (String) row.get("ProcessDefName");
					bw.activity = (String) row.get("Name");			
					bw.hasErrors = (Long) row.get("ErrorCount");
					if (Long.valueOf(0L).equals(bw.hasErrors))
						bw.hasErrors = null;
					Long executionCount = (Long) row.get("ExecutionCount"); 
					Long executionTime = (Long) row.get("ExecutionTime");
					Long elapsedTime = (Long) row.get("ElapsedTime");
					/* we cannot update avgTime stats when there are in flight activities */
					if (executionCount!=null && executionCount > 0 && bw.hasErrors!=null)
						bw.avgExecutionTime = roundDouble((double)executionTime / (double)executionCount, 2);
					if (executionCount!=null && executionCount > 0 && bw.hasErrors!=null)
						bw.avgElapsedTime = roundDouble((double)elapsedTime / (double)executionCount, 2);
					if (executionCount!=null && executionCount >= 1)
						bw.createdJobs = executionCount;
					
					bw.minElapsedTime = (Long) row.get("MinElapsedTime");
					bw.maxElapsedTime = (Long) row.get("MaxElapsedTime");
					bw.lastElapsedTime = (Long) row.get("MostRecentElapsedTime");
										
					bw.trimNames();
					if (bw.isRelevant(config)) {						
						list.add(bw);
					}
				}
			}
			return list;
		}
		
		public static List<BWStats> fromRunningStats(TabularDataSupport processes, BWJavainvoke config) {
			LinkedList<BWStats> list = new LinkedList<>();
			if (processes!=null) {
				for (Object _row : processes.values()) {
					CompositeData row = (CompositeData)_row;
					BWStats bw = new BWStats();
					bw.process = (String) row.get("StarterName") + "/" + (String) row.get("MainProcessName");
					bw.activity = (String) row.get("CurrentActivityName") + "@" + row.get("Id");			
					bw.runningTime = (Long) row.get("Duration");					
					
					bw.trimNames();
					if (bw.isRelevant(config)) {						
						list.add(bw);
					}				
				}
			}
			return list;
		}
	
		/* with all activities we would end up with very huge volumes of metrics
		 * so we need to do some filtering to pass only important data
		 */
		private boolean isRelevant(BWJavainvoke config) {
			if (runningTime!=null && runningTime > 0)
				return true;			
			if (lastElapsedTime==null || Long.valueOf(0).equals(lastElapsedTime))
				return false;
			if (completedJobs!=null)
				return true;
			if (minElapsedTime!=null && minElapsedTime > config.minElapsedWarnThresholdMillis)
				return true;
			if (maxElapsedTime!=null && maxElapsedTime > config.maxElapsedWarnThresholdMillis)
				return true;
			if (hasErrors!=null && hasErrors > 0) {
				String a = ""+activity.toLowerCase();
				if (a.contains("error") && (a.contains("dummy") || a.contains("force") || (a.contains("get") && 
					(a.contains("stack")) || a.contains("path") || a.contains("process"))))
						return false; /* there are some logging patterns gathering process name/path/stack via dummy error */
				return true;
			}
			return false;
		}
	}

	public static class VMStats {
		String nameOrDesc = "";
		String prefix = "Bw_";
		int memUsage = 0;
		int memMax = 0;
		double cpuUsage = 0;
		double gcUsage = 0;
		int cores = 0;
		int threads = 0;
		int inaccessible = 0;
		int opTime = 0;
		static String host = null;
		LinkedList<BWStats> bwStats = new LinkedList<BWStats>();	
		TreeMap<String, Object> metrics = new TreeMap<String,Object>();
		final static HashMap<String,String> namesMap = getNamesMap();
		
		static HashMap<String,String> getNamesMap() {
			HashMap<String,String> namesMap = new HashMap<String,String>();
//			procs -----------memory---------- ---swap-- -----io---- -system-- ------cpu-----
//			 r  b   swpd   free   buff  cache   si   so    bi    bo   in   cs us sy id wa st
			namesMap.put("r", "ProcessesRunnable");
			namesMap.put("b", "ProcessesBlocked");
			namesMap.put("m", "MemoryAll");
			namesMap.put("swpd", "MemorySwapped");
			namesMap.put("free", "MemoryFree");
			namesMap.put("buff", "MemoryBuffers");
			namesMap.put("cache", "MemoryCache");
			namesMap.put("si", "SwappedInPerSec");
			namesMap.put("so", "SwappedOutPerSec");
			namesMap.put("bi", "IOBlocksInPerSec");
			namesMap.put("bo", "IOBlocksOutPerSec");
			namesMap.put("in", "InterruptsPerSec");
			namesMap.put("cs", "CtxSwitchesPerSec");
			namesMap.put("us", "CpuInUserspace");
			namesMap.put("sy", "CpuInSystem");
			namesMap.put("id", "CpuInIdle");
			namesMap.put("wa", "CpuInIOWait");
			namesMap.put("st", "CpuInHypervisorWait");
			return namesMap;
		}
		
		static {
			try {
				host = InetAddress.getLocalHost().getCanonicalHostName();
			}
			catch (Throwable e) {
				System.err.println("["+BWJavainvoke.class.getName()+"] Oops, cannot get hostname: "+e);
			};
		}
	
		public String toJsonString() {
			StringBuilder sb = new StringBuilder(String.format("\"vmstat\": {\"nameOrDesc\":\"%s\"",nameOrDesc));
			for (Map.Entry<String,Object> entry : getDataInMap().entrySet()) {
				sb.append(",\"").append(entry.getKey()).append("\":\"").append(entry.getValue()).append("\"");
			}
			sb.append(",\"stats\":[");
			for (BWStats stats : bwStats)
				sb.append(stats.toJsonString());
			sb.append("]}");
			return sb.toString();
		}
		
		protected TreeMap<String, Object> getDataInMap() {
			if (!metrics.isEmpty())
				return metrics;
			TreeMap<String,Object> data = new TreeMap<String,Object>();
			data.put("memUsage", memUsage);
			data.put("memMax", memMax);
			data.put("cpuUsage", cpuUsage);
			data.put("gcUsage", gcUsage);
			data.put("cores", cores);
			data.put("threads", threads);
			data.put("inaccessible", inaccessible);
			data.put("opTime", opTime);
			return data;
		}
		
		public String toPrometheusString() {
			StringBuilder sb = new StringBuilder();
			for (Map.Entry<String,Object> entry : getDataInMap().entrySet()) {
				Object val = entry.getValue();
				String name = nameOrDesc;
				if (val instanceof Object[]) {
					Object[] tab = (Object[])val;
					if (tab.length==2) {
						name = tab[0]+"";
						val = tab[1];
					}
				}
				sb.append(prefix).append(entry.getKey()).append(" { component=\"").append(name).append("\", host=\"").append(host)
				.append("\" } ").append(val).append(" ").append(now).append("\n");
			}
			for (BWStats stats : bwStats)
				sb.append(stats.toPrometheusString(nameOrDesc));
			return sb.toString();	
		}
		
		public final static Object[] getWorkingDirSize(String line) {
			int idx = line.indexOf("/");			
			if (idx!=-1) {
				String size = line.substring(0, idx).trim();
				String app = null;
				int y = line.lastIndexOf("/working");					
				if (y!=-1) {
					int x = line.indexOf("/");
					app = line.substring(x, y);									
				}
				StringBuilder numSb = new StringBuilder();
				long unit = 1;
				for (char ch : size.toCharArray()) {
					if (ch >= '0' && ch <= '9')
						numSb.append(ch);
					else if (ch=='K')
						unit = 1024;
					else if (ch=='M')
						unit = 1024*1024;
					else if (ch=='G')
						unit = 1024*1024*1024;
					else if (ch=='T')
						unit = 1024*1024*1024*1024;
					else
						unit = -1;
				}				
				if (numSb.length()==0)
					numSb.append("0");
				return new Object[] { app, Long.parseLong(numSb.toString()) * unit };
			}
			return new Object[0];
		}
		
		public final static void getWorkingDirSizeInPrometheusFormat(StringBuilder sb) {
			String workDirLocation = com.tibco.pe.plugin.PluginProperties.getProperty("tibco.clientVar.workDirLocation");
			if (workDirLocation==null  || workDirLocation.length()==0)
				return;
			List<String> lines = system("find "+workDirLocation+" -name working -exec du -sh {} \\;", INSTANCE.queryTimeoutSeconds*1000);
			for (String line : lines) {
				Object[] wd = getWorkingDirSize(line);
				if (wd.length==2) {
					String app = (String) wd[0];
					Long val = (Long) wd[1];
					sb.append("Bw_workingDirSize").append(" { component=\"").append(app).append("\", host=\"").append(host)
					.append("\" } ").append(val).append(" ").append(now).append("\n");
				}
			}			
		}
		
		public VMStats(boolean osStats) {
			if (!osStats)
				return;
			nameOrDesc = "OS";
			prefix = "OS_";
			List<String> lines = system("LC_ALL=C vmstat", INSTANCE.probingPeriodMillis);
			if (lines.size()>=3) {
				StringTokenizer stHeader = new StringTokenizer(lines.get(1));
				StringTokenizer stValues = new StringTokenizer(lines.get(2));
				while (stHeader.hasMoreTokens()) {
					String header = stHeader.nextToken();
					if (namesMap.containsKey(header))
						header = namesMap.get(header);
					metrics.put(header, stValues.nextToken());
				}
			}
			lines = system("LC_ALL=C free", INSTANCE.probingPeriodMillis);
			if (lines.size()>=2) {
				StringTokenizer st = new StringTokenizer(lines.get(1));
				st.nextToken();
				metrics.put(namesMap.get("m"), st.nextToken());
			}
			lines = system("cat /proc/cpuinfo | grep name |  cut -d: -f2,3,4,5", INSTANCE.probingPeriodMillis);
			if (!lines.isEmpty()) {
				String cpu = lines.get(0);
				metrics.put("CPUs", new Object[] { cpu.trim(), new Integer(lines.size()) });
			}			
		}		
	}
		
	private final static String prepareJmxAddress(Object vm, String address) throws Exception {
		if (address == null) {
			String javaHome = ((Properties)getSystemProperties.invoke(vm)).getProperty("java.home");
			try {
				address = ((Properties)getAgentProperties.invoke(vm)).getProperty("com.sun.management.jmxremote.localConnectorAddress");
			}
			catch (Throwable exc) {}
			if (address==null) {
				File managementAgentJarFile = new File(javaHome + File.separator + "lib" + File.separator + "management-agent.jar");
				try {
					if (managementAgentJarFile.exists())
						loadAgent.invoke(vm, managementAgentJarFile.getAbsolutePath()); // Java 9 doesn't have jar files
					else
						throw new RuntimeException("No management-agent.jar");
					address = ((Properties)getAgentProperties.invoke(vm)).getProperty("com.sun.management.jmxremote.localConnectorAddress"); // but may have agent started
				}
				catch (Throwable thr) {
					address = (String) startLocalManagementAgent.invoke(vm); // load agent Java 8+
				}					
			}
		}
		return address;
	}
	
	private final static int[] getMemoryUsage(MemoryMXBean memBean) {
		MemoryUsage heap = memBean.getHeapMemoryUsage();
	  	 MemoryUsage nonHeap = memBean.getNonHeapMemoryUsage();
	   	long memMax = heap.getMax();
	   	if (nonHeap.getMax() >= 0)
	 		memMax += nonHeap.getMax();
	   	else if (nonHeap.getCommitted() >= 0)
    		memMax += nonHeap.getCommitted();
	   	else 
	   	memMax += nonHeap.getUsed();
	    	int memUsed = (int)((heap.getUsed() + nonHeap.getUsed()) / 1024 / 1024);
	    	int memAll = (int) (memMax / 1024 / 1024);
	    	return new int[] { memUsed, memAll };
	}

	private final static double computeLoadDuringPeriod(Object osBean, long millis, AtomicLong processTime, AtomicLong wallClock) throws Exception {
		if (processTime == null || wallClock == null)
			return -100;
		if (processTime.get()==0)
			processTime.set((Long)getProcessCpuTime.invoke(osBean));
		if (wallClock.get() == 0)
			wallClock.set(System.currentTimeMillis());
		if (millis > 0) {
			try {
				Thread.sleep(millis);
			}
		    catch (InterruptedException e) {}
		}
		long wallTimeElapsed = System.currentTimeMillis() - wallClock.get();
		if (wallTimeElapsed > 0) {
			double load = ((double)((Long)(getProcessCpuTime.invoke(osBean)) - processTime.get())) / (double)wallTimeElapsed / 10000.0;
			if (Double.isInfinite(load) || Double.isNaN(load) || load < 0)
		       		load = -1;
		    	else
		       		load = roundDouble(load, 3);
		    	return load;
		}
		return 0;
	}

	private final static double[] computeLoadAndGCUsageInTimeWindow(MBeanServerConnection conn, Object osBean, int samples, int probingPeriodMillis) throws Exception {
		long gcUsageDiff = 0;
		List<GarbageCollectorMXBean> gcmbList = ManagementFactory.getGarbageCollectorMXBeans();
		long[] gcTimes = new long[gcmbList.size()];
		double load = 0;
		AtomicLong processTime = new AtomicLong(0);
		AtomicLong wallClock = new AtomicLong(0);
		computeLoadDuringPeriod(osBean, 0, processTime, wallClock);
	
	    for (int i=0; i <= samples; i++) {
			for (int j=0; j < gcmbList.size(); j++) {
				try {
		       		GarbageCollectorMXBean gcBean = null;
		       		try {
		       			gcBean = ManagementFactory.newPlatformMXBeanProxy(conn, gcmbList.get(j).getObjectName().toString(), GarbageCollectorMXBean.class);
		       		}
		       		catch (Throwable e) {
		       			continue;  // GC of this kind not present
		       		}
		       		if (i > 0)
		       			gcUsageDiff += (gcBean.getCollectionTime() - gcTimes[j]);
		       		gcTimes[j] = gcBean.getCollectionTime();
			}
			catch (Throwable t) {}
		    }
		    if (i < samples) {
		    	try {
					Thread.sleep(probingPeriodMillis);
			}
			catch (InterruptedException e) {}
		    }
		}
	
	    load = computeLoadDuringPeriod(osBean, 0, processTime, wallClock); // save 1 second and do not compute it in different code block
	    double gc = roundDouble(gcUsageDiff * 100.0 / (probingPeriodMillis * samples), 3);
	    return new double[] { load, gc };
	}

	private static void cleanupJmx(JMXConnector connector) {
		if (connector!=null) {
			try {
				// RMIConnector has deep close and lite close, we want the first one
				Method closeWithCleanup = methodCache.get(connector.getClass().getName());
				if (closeWithCleanup==null) {
					closeWithCleanup = connector.getClass().getDeclaredMethod("close", boolean.class);
					closeWithCleanup.setAccessible(true);
					methodCache.put(connector.getClass().getName(), closeWithCleanup);
				}				
				closeWithCleanup.invoke(connector, true);
			}
			catch (Throwable t) {
				try {
					connector.close();
				}
				catch (IOException e) {}
			}
		}
	}

	private static JMXConnector getConnector(Object vm, JMXServiceURL jmxUrl) throws IOException {
		setRmiProperties();
		JMXConnector jmxConnector = JMXConnectorFactory.connect(jmxUrl);
		return jmxConnector;
	}

	public static VMStats query(Object vmd, BWJavainvoke config) throws Throwable {
		if (vmd==null)
			return new VMStats(true);
		Object vm = vmAttach.invoke(null, vmd);
		long timeStart = System.currentTimeMillis();
		JMXConnector connector = null;
		try {
			Properties sysProperties = (Properties)getSystemProperties.invoke(vm);
			Properties agtProperties = (Properties)getAgentProperties.invoke(vm);
			String address = agtProperties.getProperty("com.sun.management.jmxremote.localConnectorAddress");
			MBeanServerConnection conn = null;
			for (int i=0; i < config.reconnectCount; i++) {
				boolean wasError = false;
				try {
					address = prepareJmxAddress(vm, address);
					JMXServiceURL jmxUrl = new JMXServiceURL(address);
					connector = getConnector(vm, jmxUrl);
					conn = connector.getMBeanServerConnection();
					break;
				}
				catch (Throwable e) {
					wasError = true;
					if (i==0) {
						address = null; // try to reload agent
					}
					else
						throw e;
				}
				finally {
					if (connector!=null && wasError) {
						try {
							connector.close();
						}
						catch (Exception closingExc) {}
						connector = null;
					}
				}
			}
		
		String file = sysProperties.getProperty("wrapper.tra.file");
	        MemoryMXBean memBean = ManagementFactory.newPlatformMXBeanProxy(conn, ManagementFactory.MEMORY_MXBEAN_NAME, MemoryMXBean.class);
	       	RuntimeMXBean rtBean = ManagementFactory.newPlatformMXBeanProxy(conn, ManagementFactory.RUNTIME_MXBEAN_NAME, RuntimeMXBean.class);
	       	ThreadMXBean thrBean = ManagementFactory.newPlatformMXBeanProxy(conn, ManagementFactory.THREAD_MXBEAN_NAME, ThreadMXBean.class);
	       	Object osBean = ManagementFactory.newPlatformMXBeanProxy(conn, ManagementFactory.OPERATING_SYSTEM_MXBEAN_NAME, OperatingSystemMXBeanClass);
	
	       	int[] memUsedAndAll = getMemoryUsage(memBean);
	        int memUsed = memUsedAndAll[0];
	        int memAll = memUsedAndAll[1];
	        double[] resUsage = computeLoadAndGCUsageInTimeWindow(conn, osBean, config.samplesCount, config.probingPeriodMillis);
	
	        String appId = "PID|"+rtBean.getName();
	
	       	if (file!=null) {
	       		appId = file.endsWith(".tra") ? "TRA|"+file.substring(file.lastIndexOf(File.separator+"")+1) : appId;	       						
	        }
	
	        VMStats stats = new VMStats(false);
	        stats.nameOrDesc = appId;
	        stats.memUsage = memUsed;
	        stats.memMax = memAll;
	        stats.cpuUsage = (int)resUsage[0];
	        stats.gcUsage = (int)resUsage[1];
	        stats.cores = (Integer)getAvailableProcessors.invoke(osBean);
	        stats.threads = thrBean.getThreadCount();
	        stats.opTime = (int)(System.currentTimeMillis() - timeStart - config.samplesCount*config.probingPeriodMillis);
	        stats.inaccessible = stats.opTime >= 60000 ? 1 : 0;
	
	        if (sysProperties.getProperty("VMSTAT_TOKEN", "").equals(System.getProperty("VMSTAT_TOKEN")))
	        	;	// this is me
	
	        try {
	        	Set<ObjectInstance> result = conn.queryMBeans(new ObjectName("com.tibco.bw:key=engine,name=*"), null);
	        	if (result!=null && result.size() > 0) {
	        		ObjectInstance tib = result.iterator().next();
	        		if (tib!=null) {
		        		TabularDataSupport getProcesses = (TabularDataSupport) conn.invoke(tib.getObjectName(), "GetProcessDefinitions", new Object[] {}, null);
		        		TabularDataSupport getActivities = (TabularDataSupport) conn.invoke(tib.getObjectName(), "GetActivities", new Object[] { null }, null);
		        		TabularDataSupport getRunning = (TabularDataSupport) conn.invoke(tib.getObjectName(), "GetProcesses", new Object[] { 0L, null, 0L, 0L, null }, null);
		        		
		        		stats.bwStats.addAll(BWStats.fromProcessStats(getProcesses)); 
		        		stats.bwStats.addAll(BWStats.fromRunningStats(getRunning, config));
		        		stats.bwStats.addAll(BWStats.fromActivityStats(getActivities, config));
		        		conn.invoke(tib.getObjectName(), "ResetActivityStats", new Object[] { "*" }, null);
					if (!stats.bwStats.isEmpty()) {
	       					stats.nameOrDesc = appId = "BW|"+appId.substring(4);
					}
	       			}	        		
	        	}
	        }
	        catch (Throwable exc) {
	        	errors.add("Exception while querying via JMX ("+appId+" / JVM="+vm+"): "+exceptionToString(exc));
	        }        	
	        return stats;			
		}
		finally {
			try {
				cleanupJmx(connector);
			}
			catch (Throwable exc) {
	        	errors.add("Exception while closing JMX (JVM="+vm+"): "+exceptionToString(exc));
			}
			try {
				vmDetach.invoke(vm);
			}
			catch (Throwable io) {}		
		}
	}
	
	public void invokeService() throws Throwable {		
		now = System.currentTimeMillis();
		@SuppressWarnings("unchecked")
		List<Object> list = (List<Object>) vmList.invoke(null);
		System.setProperty("VMSTAT_TOKEN", System.currentTimeMillis()+"X"+Thread.currentThread().getId());
		CountDownLatch sync = new CountDownLatch(list.size()+1);
		es = getExecutorService(threadPoolCount);
		for (final Object vmd : list) {
			es.execute(new VMStatsExtractor(vmd, sync, this));		
		}
		es.execute(new VMStatsExtractor(null,  sync, this));
		
		try {
			int timeout = Integer.parseInt(com.tibco.pe.plugin.PluginProperties.getProperty("tibco.clientVar.queryTimeoutSeconds"));
			sync.await(timeout, TimeUnit.SECONDS);
		}
		catch (Throwable e) {
			errors.add("Timeout missed while waiting for all results: "+e);
		}
		
		StringBuilder sb = new StringBuilder();		
		while (result.size() > 0) {
			String vmstat = result.remove();
			sb.append(vmstat);
		}
		BWStats.getZeroOneSignalPrometheusString(sb, this);
		VMStats.getWorkingDirSizeInPrometheusFormat(sb);
		content = sb.toString();
		errorMessages = errors.toString();
		errors.clear();
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
			catch (Throwable e) {
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
		catch (Throwable e) {
			System.out.println(header+"Invalid parameter value for "+name+": "+valueStr+"; exception="+e.getMessage());
		}
	}

	private BWJavainvoke configure() throws Exception {
		setRmiProperties();
		OperatingSystemMXBeanClass = Class.forName("com.sun.management.OperatingSystemMXBean");
		getProcessCpuTime = OperatingSystemMXBeanClass.getMethod("getProcessCpuTime");
		getAvailableProcessors = OperatingSystemMXBeanClass.getMethod("getAvailableProcessors");
		VirtualMachineClass = Class.forName("com.sun.tools.attach.VirtualMachine");
		VirtualMachineDescriptorClass = Class.forName("com.sun.tools.attach.VirtualMachineDescriptor");
		vmAttach = VirtualMachineClass.getMethod("attach", VirtualMachineDescriptorClass);
		vmDetach = VirtualMachineClass.getMethod("detach");
		vmList = VirtualMachineClass.getMethod("list");
		getSystemProperties = VirtualMachineClass.getMethod("getSystemProperties");
		getAgentProperties = VirtualMachineClass.getMethod("getAgentProperties");
		loadAgent = VirtualMachineClass.getMethod("loadAgent", String.class);
		try {
			VMSupportClass = Class.forName("jdk.internal.vm.VMSupport"); // Java 9
			getAgentProperties = VMSupportClass.getMethod("getAgentProperties");
			getAgentProperties.setAccessible(true);
		}
		catch (Throwable t) {}
		try {
			startLocalManagementAgent = VirtualMachineClass.getMethod("startLocalManagementAgent"); // Java 8+
		}
		catch (Throwable thr) {}
		String options = "maxElapsedWarnThresholdMillis minElapsedWarnThresholdMillis samplesCount probingPeriodMillis reconnectCount threadPoolCount queryTimeoutSeconds zeroOneSignalPeriodTimeMillis";
		for (String s : options.split("\\ "))
			configureParameter(s);
		if (reconnectCount < 1)
			reconnectCount = 1;
		return this;
	}
	
	private static synchronized BWJavainvoke getInstance() throws Exception {
		if (INSTANCE!=null)
			return INSTANCE;
		return (INSTANCE = new BWJavainvoke().configure());		
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
	public BWJavainvoke() {
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
	content = "<html><body><h3>This is TibcoBWExporter exposing <a href='/metrics?last=true'>metrics</a></h3></body>";
	return;
}

contentType = "text/plain; version=0.0.4";
if ("1".equals(last) || "true".equalsIgnoreCase(last) || "yes".equalsIgnoreCase(last))
		;
else {
	try {
		getInstance().invokeService();
	}
	catch (Throwable t) {
		throw new Exception(exceptionToString(t));
	}
}
content = getInstance().content;
errorMessages = getInstance().errorMessages;
}
}
