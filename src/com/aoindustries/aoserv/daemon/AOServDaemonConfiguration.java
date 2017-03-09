/*
 * Copyright 2001-2013, 2014, 2015, 2017 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon;

import com.aoindustries.io.AOPool;
import com.aoindustries.util.PropertiesUtils;
import com.aoindustries.util.StringUtility;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * The configuration for all AOServ processes is stored in a properties file.
 *
 * @author  AO Industries, Inc.
 */
final public class AOServDaemonConfiguration {

	private AOServDaemonConfiguration() {
	}

	private static Properties props;

	/**
	 * Gets a property value.
	 *
	 * @param  required  when <code>true</code> the value must be non-null and non-empty
	 */
	private static String getProperty(String name, boolean required) throws IOException {
		final String propName = "aoserv.daemon."+name;
		final String value;
		synchronized(AOServDaemonConfiguration.class) {
			if(props == null) props = PropertiesUtils.loadFromResource(AOServDaemonConfiguration.class, "aoserv-daemon.properties");
			value = props.getProperty(propName);
		}
		if(required && (value==null || value.length()==0)) throw new IOException("Required property not found: " + propName);
		return value;
	}

	/**
	 * Gets a property value, value not required.
	 */
	private static String getProperty(String name) throws IOException {
		return getProperty(name, false);
	}

	public static boolean isNested() throws IOException {
		return "true".equalsIgnoreCase(getProperty("nested"));
	}

	public static String getMonitorEmailFullTo() throws IOException {
		return getProperty("monitor.email.full.to");
	}

	public static String getMonitorEmailFullFrom() throws IOException {
		return getProperty("monitor.email.full.from");
	}

	public static String getMonitorEmailSummaryTo() throws IOException {
		return getProperty("monitor.email.summary.to");
	}

	public static String getMonitorEmailSummaryFrom() throws IOException {
		return getProperty("monitor.email.summary.from");
	}

	public static String getMonitorSmtpServer() throws IOException {
		return getProperty("monitor.smtp.server");
	}

	public static String getServerHostname() throws IOException {
		return getProperty("server.hostname", true);
	}

	public static String getSSLKeystorePassword() throws IOException {
		return getProperty("ssl.keystore.password");
	}

	public static String getSSLKeystorePath() throws IOException {
		return getProperty("ssl.keystore.path");
	}

	public static String getPostgresPassword() throws IOException {
		return getProperty("postgres.password", true);
	}

	public static int getPostgresConnections() throws IOException {
		return Integer.parseInt(getProperty("postgres.connections", true));
	}

	public static long getPostgresMaxConnectionAge() throws IOException {
		String S=getProperty("postgres.max_connection_age");
		return S==null || S.length()==0 ? AOPool.DEFAULT_MAX_CONNECTION_AGE : Long.parseLong(S);
	}

	public static String getMySqlDriver() throws IOException {
		return getProperty("mysql.driver", true);
	}

	public static String getMySqlUser() throws IOException {
		return getProperty("mysql.user", true);
	}

	public static String getMySqlPassword() throws IOException {
		return getProperty("mysql.password", true);
	}

	public static int getMySqlConnections() throws IOException {
		return Integer.parseInt(getProperty("mysql.connections", true));
	}

	public static long getMySqlMaxConnectionAge() throws IOException {
		String S=getProperty("mysql.max_connection_age");
		return S==null || S.length()==0 ? AOPool.DEFAULT_MAX_CONNECTION_AGE : Long.parseLong(S);
	}

	public static String getCyrusPassword() throws IOException {
		return getProperty("cyrus.password", true);
	}

	public static boolean isManagerEnabled(Class<?> clazz) throws IOException {
		final String stripPrefix="com.aoindustries.aoserv.daemon.";
		String key=clazz.getName();
		if(key.startsWith(stripPrefix)) key = key.substring(stripPrefix.length());
		key += ".enabled";
		String value=getProperty(key, true);
		if("true".equalsIgnoreCase(value)) return true;
		if("false".equalsIgnoreCase(value)) return false;
		throw new IOException("Value in aoserv-daemon.properties must be either \"true\" or \"false\": "+key);
	}

	public static class NetworkMonitorConfiguration {

		public enum NetworkDirection {
			in,
			out
		}

		public enum CountDirection {
			src,
			dst
		}

		private final String name;
		private final String device;
		private final List<String> networkRanges;
		private final NetworkDirection inNetworkDirection;
		private final CountDirection inCountDirection;
		private final NetworkDirection outNetworkDirection;
		private final CountDirection outCountDirection;
		private final Long nullRouteFifoErrorRate;
		private final Long nullRouteFifoErrorRateMinPps;
		private final Long nullRoutePacketRate;
		private final Long nullRouteBitRate;

		NetworkMonitorConfiguration(
			String name,
			String device,
			List<String> networkRanges,
			NetworkDirection inNetworkDirection,
			CountDirection inCountDirection,
			NetworkDirection outNetworkDirection,
			CountDirection outCountDirection,
			Long nullRouteFifoErrorRate,
			Long nullRouteFifoErrorRateMinPps,
			Long nullRoutePacketRate,
			Long nullRouteBitRate
		) {
			this.name = name;
			this.device = device;
			this.networkRanges = networkRanges;
			this.inNetworkDirection = inNetworkDirection;
			this.inCountDirection = inCountDirection;
			this.outNetworkDirection = outNetworkDirection;
			this.outCountDirection = outCountDirection;
			this.nullRouteFifoErrorRate = nullRouteFifoErrorRate;
			this.nullRouteFifoErrorRateMinPps = nullRouteFifoErrorRateMinPps;
			this.nullRoutePacketRate = nullRoutePacketRate;
			this.nullRouteBitRate = nullRouteBitRate;
		}

		public String getName() {
			return name;
		}

		public String getDevice() {
			return device;
		}

		public List<String> getNetworkRanges() {
			return networkRanges;
		}

		public NetworkDirection getInNetworkDirection() {
			return inNetworkDirection;
		}

		public CountDirection getInCountDirection() {
			return inCountDirection;
		}

		public NetworkDirection getOutNetworkDirection() {
			return outNetworkDirection;
		}

		public CountDirection getOutCountDirection() {
			return outCountDirection;
		}

		public Long getNullRouteFifoErrorRate() {
			return nullRouteFifoErrorRate;
		}

		public Long getNullRouteFifoErrorRateMinPps() {
			return nullRouteFifoErrorRateMinPps;
		}

		public Long getNullRoutePacketRate() {
			return nullRoutePacketRate;
		}

		public Long getNullRouteBitRate() {
			return nullRouteBitRate;
		}
	}

	/**
	 * Gets the set of network monitors that should be enabled on this server.
	 */
	public static Map<String, NetworkMonitorConfiguration> getNetworkMonitors() throws IOException {
		String networkNamesProp = getProperty("monitor.NetworkMonitor.networkNames");
		if(networkNamesProp == null || networkNamesProp.isEmpty()) {
			return Collections.emptyMap();
		} else {
			List<String> networkNames = StringUtility.splitStringCommaSpace(networkNamesProp);
			Map<String,NetworkMonitorConfiguration> networkMonitors = new LinkedHashMap<>(networkNames.size()*4/3+1);
			for(String name : networkNames) {
				String nullRouteFifoErrorRate = getProperty("monitor.NetworkMonitor.network."+name+".nullRoute.fifoErrorRate");
				String nullRouteFifoErrorRateMinPps = getProperty("monitor.NetworkMonitor.network."+name+".nullRoute.fifoErrorRateMinPps");
				String nullRoutePacketRate = getProperty("monitor.NetworkMonitor.network."+name+".nullRoute.packetRate");
				String nullRouteBitRate = getProperty("monitor.NetworkMonitor.network."+name+".nullRoute.bitRate");
				if(
					networkMonitors.put(
						name,
						new NetworkMonitorConfiguration(
							name,
							getProperty("monitor.NetworkMonitor.network."+name+".device", true),
							Collections.unmodifiableList(StringUtility.splitStringCommaSpace(getProperty("monitor.NetworkMonitor.network."+name+".networkRanges", true))),
							NetworkMonitorConfiguration.NetworkDirection.valueOf(getProperty("monitor.NetworkMonitor.network."+name+".in.networkDirection", true)),
							NetworkMonitorConfiguration.CountDirection.valueOf(getProperty("monitor.NetworkMonitor.network."+name+".in.countDirection", true)),
							NetworkMonitorConfiguration.NetworkDirection.valueOf(getProperty("monitor.NetworkMonitor.network."+name+".out.networkDirection", true)),
							NetworkMonitorConfiguration.CountDirection.valueOf(getProperty("monitor.NetworkMonitor.network."+name+".out.countDirection", true)),
							nullRouteFifoErrorRate==null || nullRouteFifoErrorRate.length()==0 ? null : Long.valueOf(nullRouteFifoErrorRate),
							nullRouteFifoErrorRateMinPps==null || nullRouteFifoErrorRateMinPps.length()==0 ? null : Long.valueOf(nullRouteFifoErrorRateMinPps),
							nullRoutePacketRate==null || nullRoutePacketRate.length()==0 ? null : Long.valueOf(nullRoutePacketRate),
							nullRouteBitRate==null || nullRouteBitRate.length()==0 ? null : Long.valueOf(nullRouteBitRate)
						)
					) != null
				) throw new IOException("Duplicate network name: " + name);
			}
			return Collections.unmodifiableMap(networkMonitors);
		}
	}
}
