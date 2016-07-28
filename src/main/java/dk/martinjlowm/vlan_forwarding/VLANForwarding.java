package dk.martinjlowm.vlan_forwarding;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;


// Core
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.internal.IOFSwitchService;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.core.util.AppCookie;

// Device Manager
import net.floodlightcontroller.devicemanager.IDeviceService;

// Link Discovery
import net.floodlightcontroller.linkdiscovery.ILinkDiscovery.LDUpdate;

// Routing
import net.floodlightcontroller.routing.IRoutingService;
import net.floodlightcontroller.routing.Route;

// Static Flow Entry Pusher
import net.floodlightcontroller.staticflowentry.IStaticFlowEntryPusherService;

// Topology
import net.floodlightcontroller.topology.ITopologyListener;
import net.floodlightcontroller.topology.ITopologyService;
import net.floodlightcontroller.topology.NodePortTuple;
import net.floodlightcontroller.util.FlowModUtils;

// OpenFlow
import org.projectfloodlight.openflow.protocol.OFFactory;
import org.projectfloodlight.openflow.protocol.OFFlowMod;
import org.projectfloodlight.openflow.protocol.OFPortDesc;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.action.OFActionOutput;
import org.projectfloodlight.openflow.protocol.action.OFActionSetField;
import org.projectfloodlight.openflow.protocol.action.OFActions;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.protocol.oxm.OFOxmVlanVid;
import org.projectfloodlight.openflow.protocol.oxm.OFOxms;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.IPv4Address;
import org.projectfloodlight.openflow.types.OFBufferId;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.OFVlanVidMatch;
import org.projectfloodlight.openflow.types.U64;

// Logger
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class VLANForwarding implements IFloodlightModule,
				       ITopologyListener {
	public static int FLOWMOD_DEFAULT_HARD_TIMEOUT = 0;
	public static int FLOWMOD_DEFAULT_IDLE_TIMEOUT = 0;
	public static int FLOWMOD_DEFAULT_PRIORITY = 1;

	private static Logger log;

	protected IDeviceService deviceManagerService;
	protected IFloodlightProviderService floodlightProviderService;
	protected IOFSwitchService switchService;
	protected IRoutingService routingEngineService;
	protected IStaticFlowEntryPusherService flowService;
	protected ITopologyService topologyService;

	protected short vlanID;

	public static final int APP_ID = 1337;
	static {
		AppCookie.registerApp(APP_ID, "VLANForwarder");
	}
	public static final U64 appCookie = AppCookie.makeCookie(APP_ID, 0);

	protected HashMap<DatapathId, U64> swBandwidth;

	//
	// IFloodlightModule
	//
	@Override
	public Collection<Class<? extends IFloodlightService>>
		getModuleServices() {
		return null;
	}

	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService>
		getServiceImpls() {
		return null;
	}

	@Override
	public Collection<Class<? extends IFloodlightService>>
		getModuleDependencies() {
		Collection<Class<? extends IFloodlightService>> l =
			new ArrayList<Class<? extends IFloodlightService>>();

		l.add(IDeviceService.class);
		l.add(IFloodlightProviderService.class);
		l.add(IOFSwitchService.class);
		l.add(IRoutingService.class);
		l.add(IStaticFlowEntryPusherService.class);
		l.add(ITopologyService.class);

		return l;
	}

	@Override
	public void init(FloodlightModuleContext context)
		throws FloodlightModuleException {
		floodlightProviderService = context
			.getServiceImpl(IFloodlightProviderService.class);
		flowService = context
			.getServiceImpl(IStaticFlowEntryPusherService.class);
		routingEngineService = context
			.getServiceImpl(IRoutingService.class);
		switchService = context
			.getServiceImpl(IOFSwitchService.class);
		topologyService = context
			.getServiceImpl(ITopologyService.class);

		vlanID = 0x002;

		log = LoggerFactory.getLogger(VLANForwarding.class);
	}

	@Override
	public void startUp(FloodlightModuleContext context) {
		topologyService.addListener(this);
	}


	//
	// ITopologyService
	//
	@Override
	public void topologyChanged(List<LDUpdate> appliedUpdates) {
		flowService.deleteAllFlows();

		Set<DatapathId> switches = switchService.getAllSwitchDpids();
		Set<IOFSwitch> edge_switches = new HashSet<IOFSwitch>();

		Map<IOFSwitch, Set<OFPort>> edge_switches_ports =
			new HashMap<IOFSwitch, Set<OFPort>>();

		log.info("topologyService: {}", topologyService);

		IOFSwitch sw;
		Set<OFPort> allKnownPorts;
		for (DatapathId dpid : switches) {
			sw = switchService.getSwitch(dpid);

			Set<OFPort> edge_ports = new HashSet<OFPort>();
			for (OFPortDesc pd : sw.getPorts()) {
				edge_ports.add(pd.getPortNo());
			}

			allKnownPorts = topologyService
				.getPortsWithLinks(sw.getId());

			if (null == allKnownPorts) {
				continue;
			}

			edge_ports.removeAll(allKnownPorts);
			edge_ports.remove(OFPort.LOCAL);
			log.info("edge_ports: {}", edge_ports);

			if (edge_ports.isEmpty()) {
				continue;
			}

			edge_switches_ports.put(sw, edge_ports);
		}

		List<IOFSwitch> of_edge_switches =
			new ArrayList<IOFSwitch>(edge_switches_ports.keySet());

		int num_edge_switches = of_edge_switches.size();

		// Find bidirectional routes in the cluster
		IOFSwitch i_sw, e_sw;
		Set<OFPort> ingress_ports, egress_ports;
		for (int ingress_idx = 0;
		     ingress_idx < num_edge_switches - 1;
		     ingress_idx++) {
			i_sw = of_edge_switches.get(ingress_idx);
			ingress_ports = edge_switches_ports.get(i_sw);

			for (int egress_idx = ingress_idx + 1;
			     egress_idx < num_edge_switches;
			     egress_idx++) {
				e_sw = of_edge_switches.get(egress_idx);
				egress_ports = edge_switches_ports.get(e_sw);

				for (OFPort i_port : ingress_ports) {
					for (OFPort e_port : egress_ports) {
						pushRoute(i_sw.getId(),
							  i_port,
							  e_sw.getId(),
							  e_port);
					}
				}
			}
		}
	}

	public void pushRoute(DatapathId iSwitch, OFPort iPort,
			      DatapathId eSwitch, OFPort ePort) {
		Route route = routingEngineService.getRoute(iSwitch, iPort,
							    eSwitch, ePort,
							    U64.of(0));

		if (null == route) {
			return;
		}

		int srcNet = (((int) (iSwitch.getLong() & 0xFF)) << 4 |
			      (iPort.getPortNumber() & 0xFF));
		int dstNet = (((int) (eSwitch.getLong() & 0xFF)) << 4 |
			      (ePort.getPortNumber() & 0xFF));

		List<NodePortTuple> switchPortList = route.getPath();
		log.info("srcNet: {}, dstNet: {}", srcNet, dstNet);
		int numSwitches = switchPortList.size();
		for (int idx = numSwitches - 1; idx > 0; idx -= 2) {
			DatapathId switchDPID = switchPortList.get(idx).getNodeId();
			IOFSwitch sw = switchService.getSwitch(switchDPID);

			if (sw == null) {
				if (log.isWarnEnabled()) {
					log.warn("Unable to push route, switch at DPID {} " + "not available", switchDPID);
				}
				return;
			}

			// This must be bidirectional and therefore are in- and
			// output bad identifiers for ports
			OFPort outPort = switchPortList.get(idx).getPortId();
			OFPort inPort = switchPortList.get(idx - 1).getPortId();

			String flowName = String.format("VLAN_%s_%d_%d", sw.getId(), srcNet, dstNet);
			String flowNameReverse = "REVERSE_" + flowName;

			boolean isStartEdgeSwitch = 1 == idx;
			boolean isEndEdgeSwitch = numSwitches - 1 == idx;

			rebuildFlow(sw, flowName, inPort, outPort,
				    isStartEdgeSwitch, isEndEdgeSwitch, false,
				    srcNet, dstNet);
			rebuildFlow(sw, flowNameReverse, outPort, inPort,
				    isStartEdgeSwitch, isEndEdgeSwitch, true,
				    srcNet, dstNet);
		}

		// 0x000 and 0xFFF are reserved, and 0x001 is normally
		// used for management, hence the addition of 1
		vlanID = (short) ((vlanID % 0x0FFF) + (short) 1);
		vlanID = (short) (vlanID + (short) (vlanID == 1 ? 1 : 0));

		log.info("Pushed route {}",
			 route);
	}

	public void rebuildFlow(IOFSwitch sw,
				String flowName, OFPort inPort, OFPort outPort,
				boolean isStartEdgeSwitch,
				boolean isEndEdgeSwitch, boolean isReverse,
				int srcNet, int dstNet) {
		OFFactory factory = sw.getOFFactory();
		OFActions actions = factory.actions();

		OFActionOutput.Builder aob = actions.buildOutput();
		List<OFAction> actionList = new ArrayList<OFAction>();
		Match.Builder mb = factory.buildMatch();
		Match.Builder mb_arp = factory.buildMatch();

		// VLAN builders
		OFOxms oxms = factory.oxms();
		OFOxmVlanVid.Builder sfv = oxms.buildVlanVid();
		sfv.setValue(OFVlanVidMatch.ofVlan(vlanID));
		OFActionSetField asfv = actions.buildSetField()
			.setField(sfv.build()).build();

		// Destination VLAN IP network

		// Matches and output actions
		aob.setPort(outPort);
		aob.setMaxLen(Integer.MAX_VALUE);
		mb.setExact(MatchField.IN_PORT, inPort);
		mb_arp.setExact(MatchField.IN_PORT, inPort);

		if (isStartEdgeSwitch || isEndEdgeSwitch) {
			if ((isStartEdgeSwitch && !isReverse) ||
			    (isEndEdgeSwitch && isReverse)) {
				actionList.add(actions.pushVlan(EthType.VLAN_FRAME));
				actionList.add(asfv);
				log.info("ActionList: {}", actionList);
			}
			else {
				mb.setExact(MatchField.VLAN_VID, OFVlanVidMatch.ofVlan(vlanID));
				actionList.add(actions.popVlan());
			}

			IPv4Address subnetMask = IPv4Address.of("255.255.255.0");
			if (isStartEdgeSwitch && !isReverse) {
				IPv4Address dstIP = IPv4Address
					.of(String.format("10.0.%d.1",
							  dstNet));

				mb.setExact(MatchField.ETH_TYPE, EthType.IPv4);
				mb.setMasked(MatchField.IPV4_DST, dstIP,
					     subnetMask);

				mb_arp.setExact(MatchField.ETH_TYPE, EthType.ARP);
				mb_arp.setMasked(MatchField.ARP_TPA, dstIP,
						 subnetMask);
			} else if (isEndEdgeSwitch && isReverse) {
				IPv4Address srcIP = IPv4Address
					.of(String.format("10.0.%d.1",
							  srcNet));

				mb.setExact(MatchField.ETH_TYPE, EthType.IPv4);
				mb.setMasked(MatchField.IPV4_DST, srcIP,
					     subnetMask);

				mb_arp.setExact(MatchField.ETH_TYPE, EthType.ARP);
				mb_arp.setMasked(MatchField.ARP_TPA, srcIP,
						 subnetMask);
			}
		}
		else {
			mb.setExact(MatchField.VLAN_VID, OFVlanVidMatch.ofVlan(vlanID));
		}

		actionList.add(aob.build());


		addFlow(sw, factory, mb.build(), actionList, inPort,
			flowName);
		if ((isStartEdgeSwitch && !isReverse)
		    || (isEndEdgeSwitch && isReverse)) {
			addFlow(sw, factory, mb_arp.build(), actionList, inPort,
				flowName + "_ARP");
		}
	}

	public void addFlow(IOFSwitch sw, OFFactory factory, Match m,
			    List<OFAction> actions,
			    OFPort inPort, String flowName) {
		OFFlowMod.Builder fmb = factory.buildFlowAdd();

		fmb.setMatch(m)
			.setIdleTimeout(FLOWMOD_DEFAULT_IDLE_TIMEOUT)
			.setHardTimeout(FLOWMOD_DEFAULT_HARD_TIMEOUT)
			.setBufferId(OFBufferId.NO_BUFFER)
			.setCookie(appCookie)
			.setOutPort(inPort)
			.setPriority(FLOWMOD_DEFAULT_PRIORITY);

		FlowModUtils.setActions(fmb, actions, sw);

		flowService.addFlow(flowName, fmb.build(), sw.getId());
	}
}
