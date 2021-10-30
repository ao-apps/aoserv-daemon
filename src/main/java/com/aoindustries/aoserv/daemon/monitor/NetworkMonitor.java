/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2013, 2014, 2017, 2018, 2020, 2021  AO Industries, Inc.
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
package com.aoindustries.aoserv.daemon.monitor;

import com.aoapps.lang.io.FileUtils;
import com.aoindustries.aoserv.client.net.IpAddress;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.net.NullRouteManager;
import com.aoindustries.aoserv.daemon.posix.linux.PackageManager;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Monitoring network traffic using the ip_counts command.
 * The set of networks is configured in the aoserv-daemon.properties file.
 *
 * @author  AO Industries, Inc.
 */
@SuppressWarnings("UseOfSystemOutOrSystemErr")
public final class NetworkMonitor {

	private static final Logger logger = Logger.getLogger(NetworkMonitor.class.getName());

	private static final boolean DEBUG = false;

	private static final int MICROS_PER_SECOND = 1000000;

	private static final Map<String, NetworkMonitor> inMonitors = new LinkedHashMap<>();
	private static final Map<String, NetworkMonitor> outMonitors = new LinkedHashMap<>();

	public static void start() throws IOException {
		if(AOServDaemonConfiguration.isManagerEnabled(NetworkMonitor.class)) {
			for(AOServDaemonConfiguration.NetworkMonitorConfiguration config : AOServDaemonConfiguration.getNetworkMonitors().values()) {
				final String networkName = config.getName();
				synchronized(System.out) {
					if(!inMonitors.containsKey(networkName)) {
						System.out.print("Starting NetworkMonitor(" + networkName +", in): ");
						NetworkMonitor monitor = new NetworkMonitor(
							config.getDevice(),
							"in",
							config.getNetworkRanges(),
							config.getInNetworkDirection(),
							config.getInCountDirection(),
							config.getNullRouteFifoErrorRate(),
							config.getNullRouteFifoErrorRateMinPps(),
							config.getNullRoutePacketRate(),
							config.getNullRouteBitRate()
						);
						inMonitors.put(networkName, monitor);
						monitor.startThread();
						System.out.println("Done");
					}
					if(!outMonitors.containsKey(networkName)) {
						System.out.print("Starting NetworkMonitor(" + networkName +", out): ");
						NetworkMonitor monitor = new NetworkMonitor(
							config.getDevice(),
							"out",
							config.getNetworkRanges(),
							config.getOutNetworkDirection(),
							config.getOutCountDirection(),
							null, // Null routes only done on incoming traffic
							null, // Null routes only done on incoming traffic
							null, // Null routes only done on incoming traffic
							null  // Null routes only done on incoming traffic
						);
						outMonitors.put(networkName, monitor);
						monitor.startThread();
						System.out.println("Done");
					}
				}
			}
		}
	}

	private final String device;
	private final String direction;
	private final List<String> networkRanges;
	private final AOServDaemonConfiguration.NetworkMonitorConfiguration.NetworkDirection networkDirection;
	private final AOServDaemonConfiguration.NetworkMonitorConfiguration.CountDirection countDirection;
	private final Long nullRouteFifoErrorRate;
	private final Long nullRouteFifoErrorRateMinPps;
	private final Long nullRoutePacketRate;
	private final Long nullRouteBitRate;

	private Thread thread;

	private NetworkMonitor(
		String device,
		String direction,
		List<String> networkRanges,
		AOServDaemonConfiguration.NetworkMonitorConfiguration.NetworkDirection networkDirection,
		AOServDaemonConfiguration.NetworkMonitorConfiguration.CountDirection countDirection,
		Long nullRouteFifoErrorRate,
		Long nullRouteFifoErrorRateMinPps,
		Long nullRoutePacketRate,
		Long nullRouteBitRate
	) {
		this.device = device;
		this.direction = direction;
		this.networkRanges = networkRanges;
		this.networkDirection = networkDirection;
		this.countDirection = countDirection;
		this.nullRouteFifoErrorRate = nullRouteFifoErrorRate;
		this.nullRouteFifoErrorRateMinPps = nullRouteFifoErrorRateMinPps;
		this.nullRoutePacketRate = nullRoutePacketRate;
		this.nullRouteBitRate = nullRouteBitRate;
	}

	static class Counts {
		final long packets;
		final long bytes;
		Counts(long packets, long bytes) {
			this.packets = packets;
			this.bytes   = bytes;
		}
	}

	private static final Counts ZERO_COUNTS = new Counts(0, 0);

	private Counts readCounts(DataInputStream in) throws IOException {
		final long packets = in.readLong();
		final long bytes   = in.readLong();
		if(
			packets  == 0
			&& bytes == 0
		) return ZERO_COUNTS;
		return new Counts(
			packets,
			bytes
		);
	}

	static class ProtocolCounts {
		final long icmpPackets;
		final long icmpBytes;
		final long udpPackets;
		final long udpBytes;
		final long tcpPackets;
		final long tcpBytes;
		final long otherPackets;
		final long otherBytes;
		ProtocolCounts(
			long icmpPackets,
			long icmpBytes,
			long udpPackets,
			long udpBytes,
			long tcpPackets,
			long tcpBytes,
			long otherPackets,
			long otherBytes
		) {
			this.icmpPackets  = icmpPackets;
			this.icmpBytes    = icmpBytes;
			this.udpPackets   = udpPackets;
			this.udpBytes     = udpBytes;
			this.tcpPackets   = tcpPackets;
			this.tcpBytes     = tcpBytes;
			this.otherPackets = otherPackets;
			this.otherBytes   = otherBytes;
		}

		/**
		 * Gets the packet count for all protocols combined.
		 */
		long getPackets() {
			return icmpPackets + udpPackets + tcpPackets + otherPackets;
		}

		/**
		 * Gets the byte count for all protocols combined.
		 */
		long getBytes() {
			return icmpBytes + udpBytes + tcpBytes + otherBytes;
		}
	}

	private static final ProtocolCounts ZERO_PROTOCOL_COUNTS = new ProtocolCounts(0, 0, 0, 0, 0, 0, 0, 0);

	private ProtocolCounts readProtocolCounts(DataInputStream in) throws IOException {
		final long icmpPackets  = in.readLong();
		final long icmpBytes    = in.readLong();
		final long udpPackets   = in.readLong();
		final long udpBytes     = in.readLong();
		final long tcpPackets   = in.readLong();
		final long tcpBytes     = in.readLong();
		final long otherPackets = in.readLong();
		final long otherBytes   = in.readLong();
		if(
			icmpPackets     == 0
			&& icmpBytes    == 0
			&& udpPackets   == 0
			&& udpBytes     == 0
			&& tcpPackets   == 0
			&& tcpBytes     == 0
			&& otherPackets == 0
			&& otherBytes   == 0
		) return ZERO_PROTOCOL_COUNTS;
		return new ProtocolCounts(
			icmpPackets,
			icmpBytes,
			udpPackets,
			udpBytes,
			tcpPackets,
			tcpBytes,
			otherPackets,
			otherBytes
		);
	}

	abstract static class Network {
		Network() {
		}
	}

	static class IPv4Network extends Network {
		final int address;
		final byte prefix;
		final ProtocolCounts totalCounts;
		final ProtocolCounts[] ips;
		IPv4Network(
			int address,
			byte prefix,
			ProtocolCounts totalCounts,
			ProtocolCounts[] ips
		) {
			this.address = address;
			this.prefix = prefix;
			this.totalCounts = totalCounts;
			this.ips = ips;
		}
	}

	private static void printTime(long seconds, int micros, PrintStream out) {
		assert micros>=0 && micros <= 999999;
		out.print(seconds);
		out.print('.');
		if(micros < 10) out.print("00000");
		else if(micros < 100) out.print("0000");
		else if(micros < 1000) out.print("000");
		else if(micros < 10000) out.print("00");
		else if(micros < 100000) out.print('0');
		out.print(micros);
	}

	private static void printCounts(Counts counts, PrintStream out) {
		out.print(counts.packets);
		out.print('/');
		out.print(counts.bytes);
	}

	private static void printProtocolCounts(String name, ProtocolCounts counts, PrintStream out) {
		out.print(name);
		out.print(".icmp=");
		out.print(counts.icmpPackets);
		out.print('/');
		out.print(counts.icmpBytes);
		out.println();
		out.print(name);
		out.print(".udp=");
		out.print(counts.udpPackets);
		out.print('/');
		out.print(counts.udpBytes);
		out.println();
		out.print(name);
		out.print(".tcp=");
		out.print(counts.tcpPackets);
		out.print('/');
		out.print(counts.tcpBytes);
		out.println();
		out.print(name);
		out.print(".other=");
		out.print(counts.otherPackets);
		out.print('/');
		out.print(counts.otherBytes);
		out.println();
	}

	private static long getBitRate(long bytes, long timeSpanMicros) {
		return Math.multiplyExact(bytes, 8 * MICROS_PER_SECOND) / timeSpanMicros;
	}

	private static long getPacketRate(long packets, long timeSpanMicros) {
		return Math.multiplyExact(packets, MICROS_PER_SECOND) / timeSpanMicros;
	}

	private synchronized void startThread() {
		if(thread==null) {
			final String threadName = NetworkMonitor.class.getName()+"("+device+", "+direction+")";
			final String errorInThreadName = threadName+".errorIn";
			final boolean controllingNullRoutes = nullRouteFifoErrorRate!=null || nullRoutePacketRate!=null || nullRouteBitRate!=null;

			// Determine which sys file to watch for fifo errors
			final File fifoErrorsFile;
			if(nullRouteFifoErrorRate != null) {
				fifoErrorsFile = new File(
					"/sys/class/net/" + device + "/statistics/"
					+ (networkDirection==AOServDaemonConfiguration.NetworkMonitorConfiguration.NetworkDirection.in ? "rx_fifo_errors" : "tx_fifo_errors")
				);
			} else {
				fifoErrorsFile = null;
			}

			// Run in a separate thread
			thread = new Thread(threadName) {
				@Override
				@SuppressWarnings({"ThrowFromFinallyBlock", "SleepWhileInLoop", "BroadCatchBlock", "TooBroadCatch", "UseSpecificCatch"})
				public void run() {
					while(!Thread.currentThread().isInterrupted()) {
						try {
							final String[] cmd = new String[
								(controllingNullRoutes ? 0 : 1)
								+ 6
								+ networkRanges.size()
							];
							int index = 0;
							// No nice level when controlling null routes
							if(!controllingNullRoutes) cmd[index++] = "/bin/nice";
							cmd[index++] = "/opt/aoserv-daemon/bin/ip_counts";
							cmd[index++] = "1";
							cmd[index++] = "binary";
							cmd[index++] = device;
							cmd[index++] = networkDirection.name();
							cmd[index++] = countDirection.name();
							for(String networkRange : networkRanges) {
								cmd[index++] = networkRange;
							}
							assert index == cmd.length;

							// Make sure libpcap is installed as required by ip_counts
							PackageManager.installPackage(PackageManager.PackageName.LIBPCAP);
							final Process process = new ProcessBuilder(cmd).start();
							try {
								// Log all warnings from ip_counts to stderr
								final InputStream errorIn = process.getErrorStream();
								new Thread(errorInThreadName) {
									@Override
									@SuppressWarnings("UseSpecificCatch")
									public void run() {
										try {
											try (BufferedReader in = new BufferedReader(new InputStreamReader(errorIn))) {
												String line;
												while((line = in.readLine()) != null) {
													synchronized(System.err) {
														System.err.print(threadName);
														System.err.print(": ");
														System.err.println(line);
													}
												}
											}
										} catch (ThreadDeath td) {
											throw td;
										} catch (Throwable t) {
											logger.log(Level.SEVERE, null, t);
										}
									}
								}.start();
								// Read standard in
								try (DataInputStream in = new DataInputStream(new BufferedInputStream(process.getInputStream()))) {
									// Must be at least 5 seconds between FIFO-generate null routes
									Long lastFifoErrors = null;
									while(true) {
										if(Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
										// Read one record
										final byte protocolVersion = in.readByte();
										if(protocolVersion != 1) throw new IOException("Unexpected protocolVersion: "+protocolVersion);
										final long timeStartSeconds = in.readLong();
										final int timeStartMicros = in.readInt();
										final long timeEndSeconds = in.readLong();
										final int timeEndMicros = in.readInt();
										final long ifaceDropped = in.readLong();
										final long ifaceErrors = in.readLong();
										final long ifaceFifoErrors = in.readLong();
										final long pcapReceived = in.readLong();
										final long pcapDropped = in.readLong();
										final Counts totalIface = readCounts(in);
										final Counts totalCaptured = readCounts(in);
										final Counts totalExtrapolated = readCounts(in);
										final Counts unparseable = readCounts(in);
										final ProtocolCounts otherNetwork = readProtocolCounts(in);
										final int numNetworks = in.readInt();
										final Network[] networks = new Network[numNetworks];
										for(int netIndex=0; netIndex<numNetworks; netIndex++) {
											final byte ipVersion = in.readByte();
											if(ipVersion == 4) {
												final int address = in.readInt();
												final byte prefix = in.readByte();
												final ProtocolCounts totalCounts = readProtocolCounts(in);
												final int numIps = 1 << (32 - prefix);
												final ProtocolCounts[] ips = new ProtocolCounts[numIps];
												for(int ipIndex=0; ipIndex<numIps; ipIndex++) {
													ips[ipIndex] = readProtocolCounts(in);
												}
												networks[netIndex] = new IPv4Network(address, prefix, totalCounts, ips);
											} else {
												throw new IOException("Unexpected ipVersion: " + ipVersion);
											}
										}
										if(DEBUG) {
											final PrintStream out = System.out;
											synchronized(out) {
												out.print(threadName);
												out.println(": Got report");
												out.print("time.start=");
												printTime(timeStartSeconds, timeStartMicros, out);
												out.println();
												out.print("time.end=");
												printTime(timeEndSeconds, timeEndMicros, out);
												out.println();
												out.print("iface.dropped=");
												out.println(ifaceDropped);
												out.print("iface.errors=");
												out.println(ifaceErrors);
												out.print("iface.fifo_errors=");
												out.println(ifaceFifoErrors);
												out.print("pcap.received=");
												out.println(pcapReceived);
												out.print("pcap.dropped=");
												out.println(pcapDropped);
												out.print("total.iface=");
												printCounts(totalIface, out);
												out.println();
												out.print("total.captured=");
												printCounts(totalCaptured, out);
												out.println();
												out.print("total.extrapolated=");
												printCounts(totalExtrapolated, out);
												out.println();
												out.print("unparseable=");
												printCounts(unparseable, out);
												out.println();
												printProtocolCounts("other_network", otherNetwork, out);
											}
										}
										// Compute time span of last sample in microseconds
										long timeSpanMicros = (timeEndSeconds - timeStartSeconds) * MICROS_PER_SECOND + (timeEndMicros - timeStartMicros);
										// Do not null route on short samples
										if(timeSpanMicros >= (MICROS_PER_SECOND/2)) {
											// Add null route on fifo errors
											if(nullRouteFifoErrorRate != null) {
												long newFifoErrors = Long.parseLong(FileUtils.readFileAsString(fifoErrorsFile).trim());
												if(
													lastFifoErrors != null
													&& newFifoErrors >= lastFifoErrors
												) {
													long fifoErrorRate = getPacketRate(newFifoErrors - lastFifoErrors, timeSpanMicros);
													if(fifoErrorRate >= nullRouteFifoErrorRate) {
														// Find host with highest packets count (all protocols combined)
														int nullingIp = 0;
														long highestPacketCount = Long.MIN_VALUE;
														for(Network network : networks) {
															if(network instanceof IPv4Network) {
																IPv4Network ipv4Network = (IPv4Network)network;
																ProtocolCounts[] ips = ipv4Network.ips;
																for(int ipIndex=0, len=ips.length; ipIndex<len; ipIndex++) {
																	long packetCount = ips[ipIndex].getPackets();
																	if(packetCount > highestPacketCount) {
																		nullingIp = ipv4Network.address + ipIndex;
																		highestPacketCount = packetCount;
																	}
																}
															} else {
																throw new AssertionError("Unexpected type of network: " + (network == null ? "null" : network.getClass().getName()));
															}
														}
														if(highestPacketCount == Long.MIN_VALUE) throw new AssertionError("Unable to find IP to null route");
														if(
															nullRouteFifoErrorRateMinPps != null
															&& highestPacketCount < nullRouteFifoErrorRateMinPps
														) {
															PrintStream out = System.out;
															synchronized(out) {
																out.print(threadName);
																out.print(": skipping FIFO error null routing due to insufficient pps (under " + nullRouteFifoErrorRateMinPps + "): ");
																out.print(fifoErrorRate);
																out.print(" FIFO errors per second >= ");
																out.print(nullRouteFifoErrorRate);
																out.print(" pps: Found highest IP ");
																out.print(IpAddress.getIPAddressForInt(nullingIp));
																out.print(" @ ");
																out.print(getPacketRate(highestPacketCount, timeSpanMicros));
																out.println(" pps");
															}
														} else {
															PrintStream out = System.out;
															synchronized(out) {
																out.print(threadName);
																out.print(": null routing: ");
																out.print(fifoErrorRate);
																out.print(" FIFO errors per second >= ");
																out.print(nullRouteFifoErrorRate);
																out.print(" pps: Found highest IP ");
																out.print(IpAddress.getIPAddressForInt(nullingIp));
																out.print(" @ ");
																out.print(getPacketRate(highestPacketCount, timeSpanMicros));
																out.println(" pps");
															}
															NullRouteManager.addNullRoute(nullingIp);
														}
													}
												}
												lastFifoErrors = newFifoErrors;
											}
											// Add null routes due to packets/sec
											if(nullRoutePacketRate != null) {
												// Compute current packets/sec
												long packetRate = getPacketRate(totalIface.packets, timeSpanMicros);
												if(packetRate >= nullRoutePacketRate) {
													// Find host with highest packets count (all protocols combined)
													int nullingIp = 0;
													long highestPacketCount = Long.MIN_VALUE;
													for(Network network : networks) {
														if(network instanceof IPv4Network) {
															IPv4Network ipv4Network = (IPv4Network)network;
															ProtocolCounts[] ips = ipv4Network.ips;
															for(int ipIndex=0, len=ips.length; ipIndex<len; ipIndex++) {
																long packetCount = ips[ipIndex].getPackets();
																if(packetCount > highestPacketCount) {
																	nullingIp = ipv4Network.address + ipIndex;
																	highestPacketCount = packetCount;
																}
															}
														} else {
															throw new AssertionError("Unexpected type of network: " + (network == null ? "null" : network.getClass().getName()));
														}
													}
													if(highestPacketCount == Long.MIN_VALUE) throw new AssertionError("Unable to find IP to null route");
													PrintStream out = System.out;
													synchronized(out) {
														out.print(threadName);
														out.print(": null routing: ");
														out.print(packetRate);
														out.print(" pps >= ");
														out.print(nullRoutePacketRate);
														out.print(" pps: Found highest IP ");
														out.print(IpAddress.getIPAddressForInt(nullingIp));
														out.print(" @ ");
														out.print(getPacketRate(highestPacketCount, timeSpanMicros));
														out.println(" pps");
													}
													NullRouteManager.addNullRoute(nullingIp);
												}
											}
											// Add null routes due to bits/sec
											if(nullRouteBitRate != null) {
												// Compute current bits/sec
												long bitRate = getBitRate(totalIface.bytes, timeSpanMicros);
												if(bitRate >= nullRouteBitRate) {
													// Find host with highest bytes count (all protocols combined)
													int nullingIp = 0;
													long highestByteCount = Long.MIN_VALUE;
													for(Network network : networks) {
														if(network instanceof IPv4Network) {
															IPv4Network ipv4Network = (IPv4Network)network;
															ProtocolCounts[] ips = ipv4Network.ips;
															for(int ipIndex=0, len=ips.length; ipIndex<len; ipIndex++) {
																long byteCount = ips[ipIndex].getBytes();
																if(byteCount > highestByteCount) {
																	nullingIp = ipv4Network.address + ipIndex;
																	highestByteCount = byteCount;
																}
															}
														} else {
															throw new AssertionError("Unexpected type of network: " + (network == null ? "null" : network.getClass().getName()));
														}
													}
													if(highestByteCount == Long.MIN_VALUE) throw new AssertionError("Unable to find IP to null route");
													PrintStream out = System.out;
													synchronized(out) {
														out.print(threadName);
														out.print(": null routing: ");
														out.print(bitRate);
														out.print(" bps >= ");
														out.print(nullRouteBitRate);
														out.print(" bps: Found highest IP ");
														out.print(IpAddress.getIPAddressForInt(nullingIp));
														out.print(" @ ");
														out.print(getBitRate(highestByteCount, timeSpanMicros));
														out.println(" bps");
													}
													NullRouteManager.addNullRoute(nullingIp);
												}
											}
										}
										// TODO: Build statistics database/history (in master since roles can change?  Or history per gateway?)
										// TODO: Report to any listeners
									}
								}
							} finally {
								int retVal = process.waitFor();
								if(retVal != 0) throw new IOException("non-zero exit code: " + retVal);
							}
						} catch (ThreadDeath td) {
							throw td;
						} catch (Throwable t) {
							logger.log(Level.SEVERE, null, t);
						}
						try {
							// Sleep less on error when controlling null routes
							sleep(controllingNullRoutes ? 1000 : 10000);
						} catch (InterruptedException err) {
							logger.log(Level.WARNING, null, err);
							// Restore the interrupted status
							Thread.currentThread().interrupt();
						}
					}
				}
			};
			// Maximum priority when controlling null routes, normal priority otherwise
			thread.setPriority(controllingNullRoutes ? Thread.MAX_PRIORITY : Thread.NORM_PRIORITY);
			thread.start();
		}
	}
}
