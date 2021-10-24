/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2006-2013, 2015, 2016, 2017, 2018, 2019, 2020, 2021  AO Industries, Inc.
 *     support@aoindustries.com
 *     7262 Bull Pen Cir
 *     Mobile, AL 36695
 *
 * This file is part of aoserv-daemon.
 *
 * aoserv-daemon is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * aoserv-daemon is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with aoserv-daemon.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.aoindustries.aoserv.daemon.net;

import com.aoapps.collections.AoCollections;
import com.aoapps.encoding.ChainWriter;
import com.aoapps.hodgepodge.io.AOPool;
import com.aoapps.io.posix.PosixFile;
import com.aoapps.lang.Throwables;
import com.aoapps.lang.util.ErrorPrinter;
import com.aoapps.net.InetAddress;
import com.aoapps.net.MacAddress;
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.client.linux.Server;
import com.aoindustries.aoserv.client.net.Bind;
import com.aoindustries.aoserv.client.net.Device;
import com.aoindustries.aoserv.client.net.DeviceId;
import com.aoindustries.aoserv.client.net.IpAddress;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ProtocolFamily;
import java.net.Socket;
import java.net.StandardProtocolFamily;
import java.nio.charset.Charset;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles the building of IP address configs and files.
 */
public final class NetDeviceManager extends BuilderThread {

	private static final Logger logger = Logger.getLogger(NetDeviceManager.class.getName());

	public static final PosixFile
		netScriptDirectory=new PosixFile("/etc/sysconfig/network-scripts"),
		networkScript=new PosixFile("/etc/sysconfig/network"),
		networkScriptNew=new PosixFile("/etc/sysconfig/network.new")
	;

	private static NetDeviceManager netDeviceManager;

	private static final boolean WARN_ONLY = false;

	private NetDeviceManager() {
	}

	private static final Object rebuildLock = new Object();
	@Override
	@SuppressWarnings({"UseSpecificCatch", "TooBroadCatch"})
	protected boolean doRebuild() {
		try {
			Server thisServer = AOServDaemon.getThisServer();
			OperatingSystemVersion osv = thisServer.getHost().getOperatingSystemVersion();
			int osvId = osv.getPkey();
			if(osvId != OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
				throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
			}

			int uid_min = thisServer.getUidMin().getId();
			int gid_min = thisServer.getGidMin().getId();

			// Used on inner loop
			final ByteArrayOutputStream bout = new ByteArrayOutputStream();

			synchronized(rebuildLock) {
				Set<DeviceId> restartDeviceIDs=new HashSet<>();

				List<Device> devices = thisServer.getHost().getNetDevices();
				for(Device device : devices) {
					DeviceId deviceId=device.getDeviceId();
					if(
						// Don't build loopback
						!deviceId.isLoopback()
						// Don't build bonded device
						&& !deviceId.getName().startsWith("bond")
					) {
						// Build the new primary IP script to RAM first
						bout.reset();
						ChainWriter out = new ChainWriter(bout);
						try {
							if(osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
								out.print("#\n"
								+ "# Automatically generated by ").print(NetDeviceManager.class.getName()).print("\n"
								+ "#\n"
								+ "DEVICE=").print(deviceId).print('\n');
								IpAddress primaryIP=device.getPrimaryIPAddress();
								if(primaryIP.isDhcp()) {
									out.print("BOOTPROTO=dhcp\n"
											+ "ONBOOT=yes\n"
											+ "NEEDHOSTNAME=no\n");
								} else {
									InetAddress network=device.getNetwork();
									if(network==null) throw new SQLException("(net_devices.pkey="+device.getPkey()+").network may not be null");
									InetAddress broadcast=device.getBroadcast();
									if(broadcast==null) throw new SQLException("(net_devices.pkey="+device.getPkey()+").broadcast may not be null");
									out.print("BOOTPROTO=static\n");
									InetAddress ip = primaryIP.getInetAddress();
									ProtocolFamily family = ip.getProtocolFamily();
									if(family.equals(StandardProtocolFamily.INET)) {
										out.print("IPADDR=").print(ip.toString()).print("\n"
												+ "NETMASK=").print(primaryIP.getNetMask()).print("\n"
												+ "NETWORK=").print(network.toString()).print("\n"
												+ "BROADCAST=").print(broadcast.toString()).print("\n");
									} else if(family.equals(StandardProtocolFamily.INET6)) {
										out.print("IPV6INIT=yes\n"
												+ "IPV6ADDR=").print(ip.toString()).print("\n"
												+ "IPV6PREFIX=\n");
									} else {
										throw new AssertionError("Unexpected family: " + family);
									}
									out.print("ONBOOT=yes\n");
								}
								MacAddress macAddr=device.getMacAddress();
								if(macAddr==null) throw new SQLException("(net_devices.pkey="+device.getPkey()+").macaddr may not be null for CentOS 5");
								out.print("HWADDR=").print(macAddr).print('\n');
							} else throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
						} finally {
							out.close();
						}
						byte[] newBytes = bout.toByteArray();

						PosixFile existing=new PosixFile(netScriptDirectory, "ifcfg-"+deviceId, false);
						if(!existing.getStat().exists() || !existing.contentEquals(newBytes)) {
							if(WARN_ONLY) {
								synchronized(System.err) {
									System.err.println("--------------------------------------------------------------------------------");
									System.err.println(existing.getPath()+" should contain the following:");
									System.err.write(newBytes);
									System.err.println("--------------------------------------------------------------------------------");
								}
							} else {
								PosixFile newFile=new PosixFile(netScriptDirectory, "ifcfg-"+deviceId+".new", false);
								try (FileOutputStream newOut = newFile.getSecureOutputStream(PosixFile.ROOT_UID, PosixFile.ROOT_GID, 0600, true, uid_min, gid_min)) {
									newOut.write(newBytes);
								}
								newFile.renameTo(existing);
							}
							if(!restartDeviceIDs.contains(deviceId)) restartDeviceIDs.add(deviceId);
						} else {
							if(WARN_ONLY) {
								synchronized(System.err) {
									System.err.println("--------------------------------------------------------------------------------");
									System.err.println(existing.getPath()+" is OK");
									System.err.println("--------------------------------------------------------------------------------");
								}
							}
						}

						// Rebuild the alias configs for this server, including all child failed-over servers IPs
						Set<String> cfgFilenames = new HashSet<>();
						String aliasBeginning="ifcfg-"+deviceId+":";
						int num=0;
						List<Server> children = thisServer.getNestedServers();
						for(int d=-1;d<children.size();d++) {
							Server linuxServer=d==-1?thisServer:children.get(d).getHost().getLinuxServer();
							Device curDevice=d==-1?device:linuxServer.getHost().getNetDevice(device.getDeviceId().getName());
							if(curDevice!=null) {
								for(IpAddress ip : curDevice.getIPAddresses()) {
									if(d!=-1 || ip.isAlias()) {
										if(ip.isDhcp()) throw new SQLException("DHCP IP aliases are not allowed for IpAddress #"+ip.getPkey());

										String filename=aliasBeginning+(num++);
										cfgFilenames.add(filename);
										// Write to RAM first
										bout.reset();
										out = new ChainWriter(bout);
										try {
											out.print("#\n"
													+ "# Automatically generated by ").print(NetDeviceManager.class.getName()).print("\n"
													+ "#\n");
											if(osvId==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
												ProtocolFamily family = ip.getInetAddress().getProtocolFamily();
												if(family.equals(StandardProtocolFamily.INET)) {
													out.print("IPADDR=").print(ip.getInetAddress().toString()).print("\n"
															+ "NETMASK=").print(ip.getNetMask()).print("\n");
												} else if(family.equals(StandardProtocolFamily.INET6)) {
													out.print("IPV6ADDR=").print(ip.getInetAddress().toString()).print("\n"
															+ "IPV6PREFIX=\n");
												} else {
													throw new AssertionError("Unexpected family: " + family);
												}
											} else throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
										} finally {
											out.close();
										}
										newBytes = bout.toByteArray();

										PosixFile cfgUF=new PosixFile(netScriptDirectory, filename, false);
										if(!cfgUF.getStat().exists() || !cfgUF.contentEquals(newBytes)) {
											if(WARN_ONLY) {
												synchronized(System.err) {
													System.err.println();
													System.err.println("--------------------------------------------------------------------------------");
													System.err.println(cfgUF.getPath()+" should contain the following:");
													System.err.write(newBytes);
													System.err.println("--------------------------------------------------------------------------------");
												}
											} else {
												PosixFile newCfgUF=new PosixFile(netScriptDirectory, filename+".new", false);
												try (FileOutputStream newOut = newCfgUF.getSecureOutputStream(PosixFile.ROOT_UID, PosixFile.ROOT_GID, 0600, true, uid_min, gid_min)) {
													newOut.write(newBytes);
												}
												newCfgUF.renameTo(cfgUF);
											}
											if(!restartDeviceIDs.contains(deviceId)) restartDeviceIDs.add(deviceId);
										} else {
											if(WARN_ONLY) {
												synchronized(System.err) {
													System.err.println();
													System.err.println("--------------------------------------------------------------------------------");
													System.err.println(cfgUF.getPath()+" is OK");
													System.err.println("--------------------------------------------------------------------------------");
												}
											}
										}
									}
								}
							}
						}

						// Remove the extra alias configs
						for(String filename : netScriptDirectory.list()) {
							if(
								!cfgFilenames.contains(filename)
								&& filename.startsWith(aliasBeginning)
							) {
								PosixFile extra=new PosixFile(netScriptDirectory, filename, false);
								if(WARN_ONLY) {
									synchronized(System.err) {
										System.err.println();
										System.err.println("--------------------------------------------------------------------------------");
										System.err.println(extra.getPath()+" should be deleted");
										System.err.println("--------------------------------------------------------------------------------");
									}
								} else {
									extra.delete();
								}
								if(!restartDeviceIDs.contains(deviceId)) restartDeviceIDs.add(deviceId);
							}
						}
					}
				}

				// Build to RAM first
				bout.reset();
				try (ChainWriter out = new ChainWriter(bout)) {
					out.print("#\n"
							+ "# Automatically generated by ").print(NetDeviceManager.class.getName()).print("\n"
							+ "#\n"
							+ "HOSTNAME=").print(thisServer.getHostname()).print("\n"
							+ "NETWORKING=yes\n");
					// There should no more than one network device with a gateway specified
					List<Device> gatewayDevices=new ArrayList<>();
					for (Device device : devices) {
						DeviceId deviceId=device.getDeviceId();
						if(!deviceId.isLoopback()) {
							if(device.getGateway()!=null) gatewayDevices.add(device);
						}
					}
					if(gatewayDevices.size()>0) {
						if(gatewayDevices.size()>1) throw new SQLException("More than one gateway device found: "+gatewayDevices.size());
						Device gateway=gatewayDevices.get(0);
						out.print("GATEWAY=").print(gateway.getGateway().toString()).print('\n');
					}
					if(osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
						out.print("NETWORKING_IPV6=yes\n");
					}
				}
				byte[] newBytes = bout.toByteArray();

				if(!networkScript.getStat().exists() || !networkScript.contentEquals(newBytes)) {
					if(WARN_ONLY) {
						synchronized(System.err) {
							System.err.println();
							System.err.println("--------------------------------------------------------------------------------");
							System.err.println(networkScript.getPath()+" should contain the following:");
							System.err.write(newBytes);
							System.err.println("--------------------------------------------------------------------------------");
						}
					} else {
						try (FileOutputStream newOut = networkScriptNew.getSecureOutputStream(PosixFile.ROOT_UID, PosixFile.ROOT_GID, 0755, true, uid_min, gid_min)) {
							newOut.write(newBytes);
						}
						networkScriptNew.renameTo(networkScript);
					}
					// Restart all devices in this scenario
					for (Device device : devices) {
						DeviceId deviceId = device.getDeviceId();
						if(
							// Don't build loopback
							!deviceId.isLoopback()
							// Don't build bonded device
							&& !deviceId.getName().startsWith("bond")
							// Don't add twice
							&& !restartDeviceIDs.contains(deviceId)
						) {
							restartDeviceIDs.add(deviceId);
						}
					}
				} else {
					if(WARN_ONLY) {
						synchronized(System.err) {
							System.err.println();
							System.err.println("--------------------------------------------------------------------------------");
							System.err.println(networkScript.getPath()+" is OK");
							System.err.println("--------------------------------------------------------------------------------");
						}
					}
				}

				// Do not restart devices when is nested or in failover, the parent server does this
				if(!AOServDaemonConfiguration.isNested() && thisServer.getFailoverServer()==null) {
					try {
						for(DeviceId deviceId : restartDeviceIDs) {
							String deviceName = deviceId.getName();
							if(WARN_ONLY) {
								synchronized(System.err) {
									System.err.println();
									System.err.println("--------------------------------------------------------------------------------");
									System.err.println(deviceName+" should be restarted");
									System.err.println("--------------------------------------------------------------------------------");
								}
							} else {
								// Restart the networking
								Throwable t0 = null;
								try {
									AOServDaemon.exec("/sbin/ifdown", deviceName);
								} catch(Throwable t) {
									t0 = Throwables.addSuppressed(t0, t);
								}
								try {
									AOServDaemon.exec("/sbin/ifup", deviceName);
								} catch(Throwable t) {
									t0 = Throwables.addSuppressed(t0, t);
								}
								if(t0 != null) {
									throw Throwables.wrap(t0, IOException.class, IOException::new);
								}
							}
						}
					} finally {
						if(restartDeviceIDs.size()>0) {
							String command;
							if(osvId==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) command = "/etc/opt/aoserv-daemon/route";
							else throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
							if(WARN_ONLY) {
								synchronized(System.err) {
									System.err.println();
									System.err.println("--------------------------------------------------------------------------------");
									System.err.println(command+" start should be called");
									System.err.println("--------------------------------------------------------------------------------");
								}
							} else {
								AOServDaemon.exec(command, "start");
							}
						}
					}
				}
			}
			return true;
		} catch(ThreadDeath td) {
			throw td;
		} catch(Throwable t) {
			logger.log(Level.SEVERE, null, t);
			return false;
		}
	}

	@SuppressWarnings("UseOfSystemOutOrSystemErr")
	public static void start() throws IOException, SQLException {
		Server thisServer = AOServDaemon.getThisServer();
		OperatingSystemVersion osv = thisServer.getHost().getOperatingSystemVersion();
		int osvId = osv.getPkey();

		synchronized(System.out) {
			if(
				// Nothing is done for these operating systems
				osvId != OperatingSystemVersion.CENTOS_5_DOM0_I686
				&& osvId != OperatingSystemVersion.CENTOS_5_DOM0_X86_64
				&& osvId != OperatingSystemVersion.CENTOS_7_DOM0_X86_64
				// Check config after OS check so config entry not needed
				&& AOServDaemonConfiguration.isManagerEnabled(NetDeviceManager.class)
				&& netDeviceManager == null
			) {
				System.out.print("Starting NetDeviceManager: ");
				// Must be a supported operating system
				if(osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
					AOServConnector conn = AOServDaemon.getConnector();
					netDeviceManager = new NetDeviceManager();
					conn.getNet().getIpAddress().addTableListener(netDeviceManager, 0);
					conn.getNet().getDevice().addTableListener(netDeviceManager, 0);
					System.out.println("Done");
				} else {
					System.out.println("Unsupported OperatingSystemVersion: " + osv);
				}
			}
		}
	}

	@Override
	public String getProcessTimerDescription() {
		return "Rebuild Net Devices";
	}

	public static String getNetDeviceBondingReport(Device netDevice) throws IOException, SQLException {
		File procFile;
		OperatingSystemVersion osv = AOServDaemon.getThisServer().getHost().getOperatingSystemVersion();
		int osvId = osv.getPkey();
		if(
			osvId == OperatingSystemVersion.CENTOS_5_DOM0_I686
			|| osvId == OperatingSystemVersion.CENTOS_5_DOM0_X86_64
		) {
			// Xen on CentOS 5 adds a "p" to the name, try that first
			procFile = new File("/proc/net/bonding/p"+netDevice.getDeviceId().getName());
			if(!procFile.exists()) procFile = new File("/proc/net/bonding/"+netDevice.getDeviceId().getName());
		} else {
			procFile = new File("/proc/net/bonding/"+netDevice.getDeviceId().getName());
		}
		String report;
		if(procFile.exists()) {
			StringBuilder sb = new StringBuilder();
			try (InputStream in = new BufferedInputStream(new FileInputStream(procFile))) {
				int ch;
				while((ch = in.read()) != -1) {
					sb.append((char)ch); // Assuming ISO-8859-1
				}
			}
			report = sb.toString();
		} else report="";
		return report;
	}

	/**
	 * Reads a file, trims the whitespace, and parses it as a long.
	 */
	private static long readLongFile(File file, StringBuilder tempSB) throws IOException {
		tempSB.setLength(0);
		try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
			int ch;
			while((ch = in.read()) != -1) {
				tempSB.append((char)ch); // Assuming ISO-8859-1
			}
		}
		return Long.parseLong(tempSB.toString().trim());
	}

	/**
	 * 32-bit Linux systems have counters that are uint and wrap at 2^32.  When
	 * combined with gigabit, they wrap in a little over 30 seconds at full throughput.
	 * The monitoring only checks the counters every 5 minutes and thus fails or is
	 * incorrect when the average bitrate exceeds about 100 Mbps.
	 * The solution is to use a background thread to check every 5 seconds to detect
	 * this wrap and increment our own counter.  If the amount of increase is too much
	 * for gigabit, then consider it a lower-level counter reset and reset our counter
	 * to match the current value.
	 */
	private static final Object _netDeviceStatisticsLock = new Object();
	private static Thread _netDeviceStatisticsThread;
	private static final Map<Device, Long> _lastTime = new HashMap<>();
	private static final Map<Device, Long> _lastTxBytes = new HashMap<>();
	private static final Map<Device, Long> _lastRxBytes = new HashMap<>();
	private static final Map<Device, Long> _lastTxPackets = new HashMap<>();
	private static final Map<Device, Long> _lastRxPackets = new HashMap<>();
	private static final Map<Device, Long> _totalTxBytes = new HashMap<>();
	private static final Map<Device, Long> _totalRxBytes = new HashMap<>();
	private static final Map<Device, Long> _totalTxPackets = new HashMap<>();
	private static final Map<Device, Long> _totalRxPackets = new HashMap<>();

	private static final long MAX_GIGABIT_BIT_RATE = 2000000000L; // Allow twice gigabit speed before assuming counter reset
	private static final long MAX_GIGABIT_PACKET_RATE = MAX_GIGABIT_BIT_RATE / (64 * 8); // Smallest packet is 64 octets
	private static final long MAX_LINK_AGGREGATION = 4;

	/**
	 * Updates all of the counts to the current values.  This is called by
	 * getNetDeviceStatisticsReport to get up-to-date values, and also
	 * called by the 32-bit wraparound detection thread to catch wraparound.
	 *
	 * All access to this method already synchronized on netDeviceStatisticsLock
	 */
	private static void updateCounts(Device netDevice, StringBuilder tempSB) throws IOException, SQLException {
		// Determine the statsDirectory
		File statsDirectory;
		OperatingSystemVersion osv = AOServDaemon.getThisServer().getHost().getOperatingSystemVersion();
		int osvId = osv.getPkey();
		if(
			(
				osvId == OperatingSystemVersion.CENTOS_5_DOM0_I686
				|| osvId == OperatingSystemVersion.CENTOS_5_DOM0_X86_64
				|| osvId == OperatingSystemVersion.CENTOS_7_DOM0_X86_64
			) && !netDevice.getDeviceId().getName().equals(DeviceId.LO)
		) {
			// Xen adds a "p" to the name or any device (except lo or non-xen devices)
			statsDirectory = new File("/sys/class/net/p"+netDevice.getDeviceId().getName()+"/statistics");
			// If doesn't exist, it is not a Xen-managed device, use its unaltered name
			if(!statsDirectory.exists()) statsDirectory = new File("/sys/class/net/"+netDevice.getDeviceId().getName()+"/statistics");
		} else {
			statsDirectory = new File("/sys/class/net/"+netDevice.getDeviceId().getName()+"/statistics");
		}

		// Determine if on 64-bit system
		boolean is64;
		String osArch = System.getProperty("os.arch");
		if("amd64".equals(osArch)) is64 = true;
		else if("i386".equals(osArch)) is64 = false;
		else if("i586".equals(osArch)) is64 = false;
		else throw new IOException("Unexpected value for os.arch: "+osArch);

		// Read the current raw values
		final long currentTime;
		final long currentTxBytes;
		final long currentRxBytes;
		final long currentTxPackets;
		final long currentRxPackets;
		try {
			currentTime = System.currentTimeMillis();
			currentTxBytes = readLongFile(new File(statsDirectory, "tx_bytes"), tempSB);
			currentRxBytes = readLongFile(new File(statsDirectory, "rx_bytes"), tempSB);
			currentTxPackets = readLongFile(new File(statsDirectory, "tx_packets"), tempSB);
			currentRxPackets = readLongFile(new File(statsDirectory, "rx_packets"), tempSB);
		} catch(IOException err) {
			// If there is any IO exception, it may indicate the device has been completely shutdown
			// and will reset its counters in the process
			_lastTime.remove(netDevice);
			_lastTxBytes.remove(netDevice);
			_lastRxBytes.remove(netDevice);
			_lastTxPackets.remove(netDevice);
			_lastRxPackets.remove(netDevice);
			_totalTxBytes.remove(netDevice);
			_totalRxBytes.remove(netDevice);
			_totalTxPackets.remove(netDevice);
			_totalRxPackets.remove(netDevice);
			throw err;
		}

		// If on a 64-bit system, just copy the data directly
		if(is64) {
			_lastTime.put(netDevice, currentTime);
			_totalTxBytes.put(netDevice, currentTxBytes);
			_totalRxBytes.put(netDevice, currentRxBytes);
			_totalTxPackets.put(netDevice, currentTxPackets);
			_totalRxPackets.put(netDevice, currentRxPackets);
		} else {
			Long lastTime = _lastTime.get(netDevice);
			Long lastTxBytes = _lastTxBytes.get(netDevice);
			Long lastRxBytes = _lastRxBytes.get(netDevice);
			Long lastTxPackets = _lastTxPackets.get(netDevice);
			Long lastRxPackets = _lastRxPackets.get(netDevice);
			if(lastTime==null || lastTxBytes==null || lastRxBytes==null || lastTxPackets==null || lastRxPackets==null) {
				// If no previous value, initialize remove the totals to indicate device reset
				_lastTime.put(netDevice, currentTime);
				_lastTxBytes.put(netDevice, currentTxBytes);
				_lastRxBytes.put(netDevice, currentRxBytes);
				_lastTxPackets.put(netDevice, currentTxPackets);
				_lastRxPackets.put(netDevice, currentRxPackets);
				_totalTxBytes.remove(netDevice);
				_totalRxBytes.remove(netDevice);
				_totalTxPackets.remove(netDevice);
				_totalRxPackets.remove(netDevice);
			} else {
				// Look for wraparound on txBytes
				final Long totalTxBytesL = _totalTxBytes.get(netDevice);
				final long oldTotalTxBytes = totalTxBytesL==null ? 0 : totalTxBytesL;
				long newTotalTxBytes;
				if(currentTxBytes>=lastTxBytes) {
					newTotalTxBytes = oldTotalTxBytes + currentTxBytes - lastTxBytes;
				} else {
					//System.err.println("NetDeviceManager: tx_bytes wraparound detected on "+netDevice);
					newTotalTxBytes = oldTotalTxBytes + currentTxBytes + 0x100000000L - lastTxBytes;
				}

				// Look for wraparound on rxBytes
				final Long totalRxBytesL = _totalRxBytes.get(netDevice);
				final long oldTotalRxBytes = totalRxBytesL==null ? 0 : totalRxBytesL;
				long newTotalRxBytes;
				if(currentRxBytes>=lastRxBytes) {
					newTotalRxBytes = oldTotalRxBytes + currentRxBytes - lastRxBytes;
				} else {
					//System.err.println("NetDeviceManager: rx_bytes wraparound detected on "+netDevice);
					newTotalRxBytes = oldTotalRxBytes + currentRxBytes + 0x100000000L - lastRxBytes;
				}

				// Look for wraparound on txPackets
				final Long totalTxPacketsL = _totalTxPackets.get(netDevice);
				final long oldTotalTxPackets = totalTxPacketsL==null ? 0 : totalTxPacketsL;
				long newTotalTxPackets;
				if(currentTxPackets>=lastTxPackets) {
					newTotalTxPackets = oldTotalTxPackets + currentTxPackets - lastTxPackets;
				} else {
					//System.err.println("NetDeviceManager: tx_packets wraparound detected on "+netDevice);
					newTotalTxPackets = oldTotalTxPackets + currentTxPackets + 0x100000000L - lastTxPackets;
				}

				// Look for wraparound on rxPackets
				final Long totalRxPacketsL = _totalRxPackets.get(netDevice);
				final long oldTotalRxPackets = totalRxPacketsL==null ? 0 : totalRxPacketsL;
				long newTotalRxPackets;
				if(currentRxPackets>=lastRxPackets) {
					newTotalRxPackets = oldTotalRxPackets + currentRxPackets - lastRxPackets;
				} else {
					//System.err.println("NetDeviceManager: rx_packets wraparound detected on "+netDevice);
					newTotalRxPackets = oldTotalRxPackets + currentRxPackets + 0x100000000L - lastRxPackets;
				}

				// Look for any indication of device reset
				long timeDiff = currentTime - lastTime;
				if(timeDiff<1000) timeDiff = 1000; // Don't let math be thrown off by a very small divisor
				long currentTxBitRate = (newTotalTxBytes - oldTotalTxBytes) * 8 * 1000 / timeDiff;
				long currentRxBitRate = (newTotalRxBytes - oldTotalRxBytes) * 8 * 1000 / timeDiff;
				long currentTxPacketRate = (newTotalTxPackets - oldTotalTxPackets) * 8 * 1000 / timeDiff;
				long currentRxPacketRate = (newTotalRxPackets - oldTotalRxPackets) * 8 * 1000 / timeDiff;
				if(
					currentTxBitRate > (MAX_GIGABIT_BIT_RATE * MAX_LINK_AGGREGATION)
					|| currentRxBitRate > (MAX_GIGABIT_BIT_RATE * MAX_LINK_AGGREGATION)
					|| currentTxPacketRate > (MAX_GIGABIT_PACKET_RATE * MAX_LINK_AGGREGATION)
					|| currentRxPacketRate > (MAX_GIGABIT_PACKET_RATE * MAX_LINK_AGGREGATION)
				) {
					// Counter reset, remove totals to indicate reset
					//System.err.println("NetDeviceManager: Device reset detected on "+netDevice);
					_totalTxBytes.remove(netDevice);
					_totalRxBytes.remove(netDevice);
					_totalTxPackets.remove(netDevice);
					_totalRxPackets.remove(netDevice);
				} else {
					_totalTxBytes.put(netDevice, newTotalTxBytes);
					_totalRxBytes.put(netDevice, newTotalRxBytes);
					_totalTxPackets.put(netDevice, newTotalTxPackets);
					_totalRxPackets.put(netDevice, newTotalRxPackets);
				}

				// Store result and counts for next iteration
				_lastTime.put(netDevice, currentTime);
				_lastTxBytes.put(netDevice, currentTxBytes);
				_lastRxBytes.put(netDevice, currentRxBytes);
				_lastTxPackets.put(netDevice, currentTxPackets);
				_lastRxPackets.put(netDevice, currentRxPackets);
			}
		}

		// On 32-bit systems, start the thread to automatically update this every 5 seconds
		if(!is64 && _netDeviceStatisticsThread==null) {
			_netDeviceStatisticsThread = new Thread("netDeviceStatisticsThread") {
				@Override
				@SuppressWarnings({"UseSpecificCatch", "TooBroadCatch", "SleepWhileInLoop"})
				public void run() {
					// Reuse these two objects to reduce heap allocation
					final List<Device> netDevices = new ArrayList<>();
					final StringBuilder tempSB = new StringBuilder();
					while(true) {
						try {
							netDevices.clear();
							synchronized(_netDeviceStatisticsLock) {
								netDevices.addAll(_lastTime.keySet());
								for(Device netDevice : netDevices) {
									try {
										updateCounts(netDevice, tempSB);
									} catch(ThreadDeath td) {
										throw td;
									} catch(Throwable t) {
										logger.log(Level.SEVERE, null, t);
									}
								}
							}
						} catch(ThreadDeath td) {
							throw td;
						} catch(Throwable t) {
							logger.log(Level.SEVERE, null, t);
						}
						try {
							Thread.sleep(5000);
						} catch(InterruptedException err) {
							logger.log(Level.WARNING, null, err);
						}
					}
				}
			};
			_netDeviceStatisticsThread.start();
		}
	}

	public static String getNetDeviceStatisticsReport(Device netDevice) throws IOException, SQLException {
		StringBuilder tempSB = new StringBuilder((20+1)*5); // ( max length of long = 20 + newline ) * 5 lines
		synchronized(_netDeviceStatisticsLock) {
			updateCounts(netDevice, tempSB);
			Long totalTxBytes = _totalTxBytes.get(netDevice);
			Long totalRxBytes = _totalRxBytes.get(netDevice);
			Long totalTxPackets = _totalTxPackets.get(netDevice);
			Long totalRxPackets = _totalRxPackets.get(netDevice);
			tempSB.setLength(0);
			tempSB
				// Add the current system time so the bit rate calculations are unaffected by network latency
				.append(_lastTime.get(netDevice)).append('\n')
				// Add the counts
				.append(totalTxBytes==null ? -1L : totalTxBytes).append('\n')
				.append(totalRxBytes==null ? -1L : totalRxBytes).append('\n')
				.append(totalTxPackets==null ? -1L : totalTxPackets).append('\n')
				.append(totalRxPackets==null ? -1L : totalRxPackets).append('\n')
			;
		}
		return tempSB.toString();
	}

	private static final List<Integer> privilegedPorts = new ArrayList<>();

	/**
	 * Gets the next privileged source port in the range 1 &lt;= port &lt;= 1023.  Will never return
	 * any port referenced in the NetBinds for this server.  Will return all ports before
	 * cycling back through the ports.  The ports are returned in a random order.
	 * The returned port may be in use, and the resulting exception must be caught and
	 * the next port tried in that case.
	 */
	public static int getNextPrivilegedPort() throws IOException, SQLException {
		synchronized(privilegedPorts) {
			if(privilegedPorts.isEmpty()) {
				List<Bind> netBinds = AOServDaemon.getThisServer().getHost().getNetBinds();
				Set<Integer> netBindPorts = AoCollections.newHashSet(netBinds.size());
				for(Bind netBind : netBinds) netBindPorts.add(netBind.getPort().getPort());
				for(Integer port=1; port<=1023; port++) {
					if(!netBindPorts.contains(port)) privilegedPorts.add(port);
				}
			}
			int size = privilegedPorts.size();
			if(size==0) throw new AssertionError("privilegedPorts is empty");
			if(size==1) return privilegedPorts.remove(0);
			return privilegedPorts.remove(AOServDaemon.getFastRandom().nextInt(size));
		}
	}

	public static String checkSmtpBlacklist(InetAddress sourceIp, InetAddress connectIp) throws IOException, SQLException {
		Charset charset = Charset.forName("US-ASCII");
		// Try 100 times maximum
		final int numAttempts = 100;
		for(int attempt=1; attempt<=numAttempts; attempt++) {
			int sourcePort = getNextPrivilegedPort();
			try {
				try (Socket socket = new Socket()) {
					socket.setKeepAlive(true);
					socket.setSoLinger(true, AOPool.DEFAULT_SOCKET_SO_LINGER);
					//socket.setTcpNoDelay(true);
					socket.setSoTimeout(60000);
					socket.bind(new InetSocketAddress(sourceIp.toString(), sourcePort));
					socket.connect(new InetSocketAddress(connectIp.toString(), 25), 60*1000);

					try (
						PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), charset));
						BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), charset))
					) {
						// Status line
						String line = in.readLine();
						if(line==null) throw new EOFException("End of file reading status");
						out.println("QUIT");
						out.flush();
						return line;
					}
				}
			} catch(IOException err) {
				// TODO: Catch specific exception for local port in use
				ErrorPrinter.printStackTraces(err, System.err);
				throw err;
			}
		}
		throw new IOException("Unable to find available privileged port after "+numAttempts+" attempts");
	}
}
