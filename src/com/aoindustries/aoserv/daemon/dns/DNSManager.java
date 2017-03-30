/*
 * Copyright 2000-2013, 2014, 2015, 2016, 2017 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.dns;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.DNSZone;
import com.aoindustries.aoserv.client.LinuxGroup;
import com.aoindustries.aoserv.client.LinuxServerGroup;
import com.aoindustries.aoserv.client.NetBind;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.client.Protocol;
import com.aoindustries.aoserv.client.Server;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.ftp.FTPManager;
import com.aoindustries.aoserv.daemon.unix.linux.PackageManager;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.encoding.ChainWriter;
import com.aoindustries.firewalld.Service;
import com.aoindustries.firewalld.ServiceSet;
import com.aoindustries.firewalld.Target;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.net.AddressFamily;
import com.aoindustries.net.InetAddress;
import com.aoindustries.net.InetAddressPrefix;
import com.aoindustries.net.InetAddressPrefixes;
import com.aoindustries.net.Port;
import com.aoindustries.util.WrappedException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * Handles the building of name server processes and files.
 * 
 * TODO: Had to put "DISABLE_ZONE_CHECKING="yes"" in /etc/sysconfig/named due
 *       to check finding "has no address records" for MX records.  We should
 *       check for this and enable zone checking.
 *
 * @author  AO Industries, Inc.
 */
final public class DNSManager extends BuilderThread {

	/**
	 * The IPs allowed to query non-AO names.
	 *
	 * TODO: This should be configured via a new aoserv table instead of hard-coded.
	 */
	private static final String ACL =
		// Private IP addresses used internally
		  "10.0.0.0/8;"
		+ " 172.16.0.0/24;"
		+ " 192.168.0.0/16;"
		// Loopback IP
		+ " 127.0.0.0/8;"
		// Kansas City
		+ " 207.126.57.0/24;"  // Hosts
		// Fremont
		//+ " 64.71.143.176/29;" // Firewalls
		+ " 66.160.183.0/24;"  // Virtual Servers
		+ " 64.62.174.0/24;"   // Virtual Servers
		+ " 64.71.144.0/25;"   // Virtual Servers
		//+ " 66.220.7.80/29;"   // gtapolicemods.com
		// Fremont Management 
		+ " 65.19.176.24/29;" // Firewalls
		+ " 66.220.7.0/27;"  // Hosts
		// Amsterdam
		//+ " 64.62.145.40/29;"  // Firewalls
		// Mobile
		// + " 50.242.159.138;"     // 7262 Bull Pen Cir
		// Spain
		//+ " 81.19.103.96/28;"  // Firewalls
		//+ " 81.19.103.64/27;"  // Hosts
		// Secure Medical
		//+ " 66.17.86.0/24;"
	;

	private static final UnixFile
		newConfFile = new UnixFile("/etc/named.conf.new"),
		confFile = new UnixFile("/etc/named.conf")
	;

	private static final UnixFile namedZoneDir = new UnixFile("/var/named");

	/**
	 * The firewalld zones for CentOS 7.
	 */
	private static final Set<String> centos7Zones = Collections.singleton("public");

	/**
	 * Files and directories in /var/named that are never removed.
	 */
	private static final String[] centos5StaticFiles = {
		"chroot",
		"data",
		"localdomain.zone",
		"localhost.zone",
		"named.broadcast",
		"named.ca",
		"named.ip6.local",
		"named.local",
		"named.zero",
		"slaves"
	},
	centos7StaticFiles = {
		"data",
		"dynamic",
		"named.ca",
		"named.empty",
		"named.localhost",
		"named.loopback",
		"slaves"
	};
	private static String[] getStaticFiles(int osv) throws IllegalArgumentException {
		if(osv == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) return centos5StaticFiles;
		if(osv == OperatingSystemVersion.CENTOS_7_X86_64) return centos7StaticFiles;
		throw new IllegalArgumentException("Unsupported OperatingSystemVersion: " + osv);
	}

	private static DNSManager dnsManager;

	private DNSManager() {
	}

	/**
	 * Each zone is only rebuild if the zone file does not exist or its serial has changed.
	 */
	private static final Map<DNSZone,Long> zoneSerials = new HashMap<>();

	private static final Object rebuildLock = new Object();
	@Override
	protected boolean doRebuild() {
		try {
			AOServConnector connector = AOServDaemon.getConnector();
			AOServer thisAoServer = AOServDaemon.getThisAOServer();
			Server thisServer = thisAoServer.getServer();
			OperatingSystemVersion osv = thisServer.getOperatingSystemVersion();
			int osvId = osv.getPkey();
			if(
				osvId != OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
				&& osvId != OperatingSystemVersion.CENTOS_7_X86_64
			) throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);

			int uid_min = thisAoServer.getUidMin().getId();
			int gid_min = thisAoServer.getGidMin().getId();

			synchronized(rebuildLock) {
				Protocol dns = AOServDaemon.getConnector().getProtocols().get(Protocol.DNS);
				if(dns == null) throw new SQLException("Unable to find Protocol: " + Protocol.DNS);
				List<NetBind> netBinds = thisServer.getNetBinds(dns);
				if(!netBinds.isEmpty()) {
					final int namedGid;
					{
						LinuxServerGroup lsg = thisAoServer.getLinuxServerGroup(LinuxGroup.NAMED);
						if(lsg == null) throw new SQLException("Unable to find LinuxServerGroup: " + LinuxGroup.NAMED + " on " + thisAoServer.getHostname());
						namedGid = lsg.getGid().getId();
					}

					// Only restart when needed
					boolean[] needsRestart = {false};

					// Has binds, install package(s) as needed
					if(osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
						PackageManager.installPackages(
							PackageManager.PackageName.BIND,
							PackageManager.PackageName.CACHING_NAMESERVER
						);
					} else if(osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
						PackageManager.installPackage(
							PackageManager.PackageName.BIND,
							() -> {
								try {
									AOServDaemon.exec("/usr/bin/systemctl", "enable", "named");
								} catch(IOException e) {
									throw new WrappedException(e);
								}
								needsRestart[0] = true;
							}
						);
					} else {
						throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
					}

					// Keep track of all files that should NOT be deleted in /var/named
					List<String> files = new ArrayList<>();

					// This buffer is used throughout the rest of the method
					ByteArrayOutputStream bout = new ByteArrayOutputStream();

					/*
					 * Create the new /var/named files
					 */
					// By getting the list first, we get a snap-shot of the data
					List<DNSZone> zones = connector.getDnsZones().getRows();
					for(DNSZone zone : zones) {
						String file = zone.getFile();
						long serial = zone.getSerial();
						Long lastSerial = zoneSerials.get(zone);
						UnixFile realFile = new UnixFile(namedZoneDir, file, false);
						Stat realFileStat = realFile.getStat();
						if(
							lastSerial == null
							|| lastSerial != serial
							|| !realFileStat.exists()
						) {
							// Build to a memory buffer
							byte[] newContents;
							{
								bout.reset();
								try (PrintWriter out = new PrintWriter(bout)) {
									zone.printZoneFile(out);
								}
								newContents = bout.toByteArray();
							}
							if(!realFileStat.exists() || !realFile.contentEquals(newContents)) {
								UnixFile newFile = new UnixFile(namedZoneDir, file + ".new", false);
								try(OutputStream newOut = newFile.getSecureOutputStream(
									UnixFile.ROOT_UID,
									namedGid,
									0640,
									true,
									uid_min,
									gid_min
								)) {
									newOut.write(newContents);
								}
								newFile.renameTo(realFile);
								needsRestart[0] = true;
							}
							zoneSerials.put(zone, serial);
						}
						files.add(file);
					}

					/*
					 * Create the new /etc/named.conf file
					 */
					byte[] newContents;
					{
						bout.reset();
						try (ChainWriter out = new ChainWriter(bout)) {
							out.print("//\n"
									+ "// named.conf\n"
									+ "//\n"
									+ "// Generated by ").print(DNSManager.class.getName()).print("\n"
									+ "//\n"
									+ "\n");
							if(osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
								out.print("options {\n"
										+ "\tdirectory \"").print(namedZoneDir.getPath()).print("\";\n"
										+ "\tlisten-on-v6 port 53 { ::1; };\n"
										+ "\tdump-file \"/var/named/data/cache_dump.db\";\n"
										+ "\tstatistics-file \"/var/named/data/named_stats.txt\";\n"
										+ "\tmemstatistics-file \"/var/named/data/named_mem_stats.txt\";\n"
										// query-source equal to 53 also made it harder to distinguish incoming/outgoing queries on firewall
										// safe-mail.net didn't resolve with this source port: + "\tquery-source port 53;\n"
										// safe-mail.net didn't resolve with this source port: + "\tquery-source-v6 port 53;\n"
										+ "\tallow-transfer { none; };\n"
										+ "\tnotify no;\n"
										//+ "\talso-notify { none; };\n"
										+ "\tallow-query { " + ACL + " };\n"
										+ "\tallow-recursion { " + ACL + " };\n");
								Map<Integer,Set<InetAddress>> alreadyAddedIPs = new HashMap<>();
								for(NetBind nb : netBinds) {
									int port = nb.getPort().getPort();
									InetAddress ip = nb.getIPAddress().getInetAddress();
									Set<InetAddress> ips = alreadyAddedIPs.get(port);
									if(ips==null) alreadyAddedIPs.put(port, ips = new HashSet<>());
									if(ips.add(ip)) {
										switch(ip.getAddressFamily()) {
											case INET :
												out.print("\tlisten-on port ").print(port).print(" { ").print(ip.toString()).print("; };\n");
												break;
											case INET6 :
												out.print("\tlisten-on-v6 port ").print(port).print(" { ").print(ip.toString()).print("; };\n");
												break;
											default :
												throw new AssertionError();
										}
									}
								}
								out.print("};\n"
										+ "logging {\n"
										+ "\tchannel default_debug {\n"
										+ "\t\tfile \"data/named.run\";\n"
										+ "\t\tseverity dynamic;\n"
										+ "\t};\n"
										+ "};\n"
										+ "include \"/etc/named.rfc1912.zones\";\n");
								for(DNSZone zone : zones) {
									String file = zone.getFile();
									out.print("\n"
											+ "zone \"").print(zone.getZone()).print("\" IN {\n"
											+ "\ttype master;\n"
											+ "\tfile \"").print(file).print("\";\n"
											+ "\tallow-query { any; };\n"
											+ "\tallow-update { none; };\n");
									if(zone.isArpa()) {
										// Allow notify HE slaves and allow transfer
										// TODO: This should also be config/tables, not hard-coded
										out.print("\tallow-transfer { 216.218.133.2; };\n"
												+ "\tnotify explicit;\n"
												+ "\talso-notify { 216.218.130.2; 216.218.131.2; 216.218.132.2; 216.66.1.2; 216.66.80.18; };\n");
									}
									out.print("};\n");
								}
							} else if(osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
								out.print("options {\n");
								// Find all unique InetAddresses per port
								Map<Integer,Set<InetAddress>> ipsPerPortV4 = new HashMap<>();
								Map<Integer,Set<InetAddress>> ipsPerPortV6 = new HashMap<>();
								for(NetBind nb : netBinds) {
									int port = nb.getPort().getPort();
									InetAddress ip = nb.getIPAddress().getInetAddress();
									Map<Integer,Set<InetAddress>> ipsPerPort;
									switch(ip.getAddressFamily()) {
										case INET :
											ipsPerPort = ipsPerPortV4;
											break;
										case INET6 :
											ipsPerPort = ipsPerPortV6;
											break;
										default :
											throw new AssertionError();
									}
									Set<InetAddress> ips = ipsPerPort.get(port);
									if(ips == null) ipsPerPort.put(port, ips = new LinkedHashSet<>());
									ips.add(ip);
								}
								for(Map.Entry<Integer,Set<InetAddress>> entry : ipsPerPortV4.entrySet()) {
									out.print("\tlisten-on port ").print(entry.getKey()).print(" {");
									for(InetAddress ip : entry.getValue()) {
										assert ip.getAddressFamily() == AddressFamily.INET;
										out.print(' ').print(ip.toString()).print(';');
									}
									out.print(" };\n");
								}
								for(Map.Entry<Integer,Set<InetAddress>> entry : ipsPerPortV6.entrySet()) {
									out.print("\tlisten-on-v6 port ").print(entry.getKey()).print(" {");
									for(InetAddress ip : entry.getValue()) {
										assert ip.getAddressFamily() == AddressFamily.INET6;
										out.print(' ').print(ip.toString()).print(';');
									}
									out.print(" };\n");
								}
								out.print("\tdirectory \"").print(namedZoneDir.getPath()).print("\";\n"
										+ "\tdump-file \"/var/named/data/cache_dump.db\";\n"
										+ "\tstatistics-file \"/var/named/data/named_stats.txt\";\n"
										+ "\tmemstatistics-file \"/var/named/data/named_mem_stats.txt\";\n"
										+ "\n"
										+ "\tallow-query { " + ACL + " };\n"
										//+ "\trecursion yes;\n"
										+ "\tallow-recursion { " + ACL + " };\n"
										+ "\tallow-query-cache { " + ACL + " };\n"
										+ "\n"
										+ "\tallow-transfer { none; };\n"
										+ "\tnotify no;\n"
										//+ "\talso-notify { none; };\n"
										+ "\n"
										+ "\tdnssec-enable yes;\n"
										+ "\tdnssec-validation yes;\n"
										+ "\n"
										+ "\t/* Path to ISC DLV key */\n"
										+ "\tbindkeys-file \"/etc/named.iscdlv.key\";\n"
										+ "\n"
										+ "\tmanaged-keys-directory \"/var/named/dynamic\";\n"
										+ "\n"
										+ "\tpid-file \"/run/named/named.pid\";\n"
										+ "\tsession-keyfile \"/run/named/session.key\";\n"
										+ "};\n"
										+ "\n"
										+ "logging {\n"
										+ "\tchannel default_debug {\n"
										+ "\t\tfile \"data/named.run\";\n"
										+ "\t\tseverity dynamic;\n"
										+ "\t};\n"
										+ "};\n"
										+ "\n"
										+ "zone \".\" IN {\n"
										+ "\ttype hint;\n"
										+ "\tfile \"named.ca\";\n"
										+ "};\n"
										+ "\n"
										+ "include \"/etc/named.rfc1912.zones\";\n"
										+ "include \"/etc/named.root.key\";\n");
								for(DNSZone zone : zones) {
									String file = zone.getFile();
									out.print("\n"
											+ "zone \"").print(zone.getZone()).print("\" IN {\n"
											+ "\ttype master;\n"
											+ "\tfile \"").print(file).print("\";\n"
											+ "\tallow-query { any; };\n"
											+ "\tallow-update { none; };\n");
									if(zone.isArpa()) {
										// Allow notify HE slaves and allow transfer
										// TODO: This should also be config/tables, not hard-coded
										out.print("\tallow-transfer { 216.218.133.2; };\n"
												+ "\tnotify explicit;\n"
												+ "\talso-notify { 216.218.130.2; 216.218.131.2; 216.218.132.2; 216.66.1.2; 216.66.80.18; };\n");
									}
									out.print("};\n");
								}
							} else {
								throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
							}
						}
						newContents = bout.toByteArray();
					}
					if(!confFile.getStat().exists() || !confFile.contentEquals(newContents)) {
						needsRestart[0] = true;
						try (OutputStream newOut = newConfFile.getSecureOutputStream(
							UnixFile.ROOT_UID,
							namedGid,
							0640,
							true,
							uid_min,
							gid_min
						)) {
							newOut.write(newContents);
						}
						newConfFile.renameTo(confFile);
					}

					/*
					 * Restart the daemon
					 */
					if(needsRestart[0]) restart();

					/*
					 * Remove any files that should no longer be in /var/named
					 */
					files.addAll(Arrays.asList(getStaticFiles(osvId)));
					FTPManager.trimFiles(namedZoneDir, files);
				} else {
					// No binds, uninstall package(s)
					if(osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
						PackageManager.removePackage(PackageManager.PackageName.CACHING_NAMESERVER);
						PackageManager.removePackage(PackageManager.PackageName.BIND);
					} else if(osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
						// TODO: Remove firewalld service
						PackageManager.removePackage(PackageManager.PackageName.BIND);
					} else {
						throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
					}
				}
				if(
					osvId == OperatingSystemVersion.CENTOS_7_X86_64
					// Manage firewalld if installed
					&& PackageManager.getInstalledPackage(PackageManager.PackageName.FIREWALLD) != null
				) {
					List<Target> targets = new ArrayList<>(netBinds.size());
					for(NetBind nb : netBinds) {
						InetAddress ip = nb.getIPAddress().getInetAddress();
						// Assume can access self
						if(!ip.isLoopback()) {
							targets.add(
								new Target(
									InetAddressPrefix.valueOf(
										ip,
										ip.isUnspecified() ? 0 : ip.getAddressFamily().getMaxPrefix()
									),
									nb.getPort()
								)
							);
						}
					}
					ServiceSet.createOptimizedServiceSet(
						new Service(
							"named",
							null,
							"named",
							"Berkeley Internet Name Domain (DNS)",
							Arrays.asList(
								Port.valueOf(53, com.aoindustries.net.Protocol.TCP),
								Port.valueOf(53, com.aoindustries.net.Protocol.UDP)
							),
							Collections.emptySet(), // protocols
							Collections.emptySet(), // sourcePorts
							Collections.emptySet(), // modules
							InetAddressPrefixes.UNSPECIFIED_IPV4,
							InetAddressPrefixes.UNSPECIFIED_IPV6
						),
						targets
					).commit(centos7Zones);
				}
			}
			return true;
		} catch(ThreadDeath TD) {
			throw TD;
		} catch(Throwable T) {
			LogFactory.getLogger(DNSManager.class).log(Level.SEVERE, null, T);
			return false;
		}
	}

	private static final Object restartLock = new Object();
	private static void restart() throws IOException, SQLException {
		Protocol dns = AOServDaemon.getConnector().getProtocols().get(Protocol.DNS);
		if(dns == null) throw new SQLException("Unable to find Protocol: " + Protocol.DNS);
		Server thisServer = AOServDaemon.getThisAOServer().getServer();
		if(!thisServer.getNetBinds(dns).isEmpty()) {
			OperatingSystemVersion osv = thisServer.getOperatingSystemVersion();
			int osvId = osv.getPkey();
			synchronized(restartLock) {
				if(osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
					AOServDaemon.exec("/etc/rc.d/init.d/named", "restart");
				} else if(osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
					AOServDaemon.exec("/usr/bin/systemctl", "reload-or-restart", "named");
				} else {
					throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
				}
			}
		}
	}

	public static void start() throws IOException, SQLException {
		OperatingSystemVersion osv = AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion();
		int osvId = osv.getPkey();
		synchronized(System.out) {
			if(
				// Nothing is done for these operating systems
				osvId != OperatingSystemVersion.CENTOS_5_DOM0_I686
				&& osvId != OperatingSystemVersion.CENTOS_5_DOM0_X86_64
				&& osvId != OperatingSystemVersion.CENTOS_7_DOM0_X86_64
				// Check config after OS check so config entry not needed
				&& AOServDaemonConfiguration.isManagerEnabled(DNSManager.class)
				&& dnsManager == null
			) {
				System.out.print("Starting DNSManager: ");
				// Must be a supported operating system
				if(
					osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
					|| osvId == OperatingSystemVersion.CENTOS_7_X86_64
				) {
					AOServConnector conn = AOServDaemon.getConnector();
					dnsManager = new DNSManager();
					conn.getDnsZones().addTableListener(dnsManager, 0);
					conn.getDnsRecords().addTableListener(dnsManager, 0);
					conn.getNetBinds().addTableListener(dnsManager, 0);
					System.out.println("Done");
				} else {
					System.out.println("Unsupported OperatingSystemVersion: " + osv);
				}
			}
		}
	}

	@Override
	public String getProcessTimerDescription() {
		return "Rebuild DNS";
	}
}
