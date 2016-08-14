# Floodlight Module: VLAN Forwarding #

Adds static flow entries to a cluster of switches for pure VLAN forwarding.

As per the IEEE 802.1 standard, the VLAN identifers: 0x000, 0x001 and 0xFFF are
reserved. This modules uses all values but these to accomplish VLAN forwarding
across a software-defined network.

This module comes as two implementations: an internal Floodlight and an external
module that interfaces with the REST API of Floodlight.

Both solutions push static flows that are only updated if a change in the
network's topology changes. Idle and hard timeouts of flows are ignored.

External links are identified by a unique subnet constructed as follows and are
assigned a unique VLAN identifier:

```
SubnetId = (SwitchId << 4) | PortNumber
```

As of this moment, only IPv4 and ARP are supported.

## Internal ##

The internal module listens to topology changes and assigns every tunnel between
all links a unique VLAN identifier and pushes the corresponding flow entries to
the switches on the shortest path.

This implementation resides in `src/java/` under the package name
`dk.martinjlowm.vlan_forwarding`.

### Installation ###
- Create a `modules` directory in the Floodlight root.

- Add a new `modules` property to Floodlight's build.xml file that points to the
  `modules` directory.

```xml
<property name="modules" location="modules"/>
```

- Add this new property to the `javac` compile target.
```xml
srcdir="${source}:${modules}:${thrift.out.dir}"
```

- Clone this repository into the `modules` directory.

- Add `dk.martinjlowm.vlan_forwarding.VLANForwarding` in
  floodlightdefault.properties to enable the module. NOTE: You may have to
  disable the normal Forwarding module to avoid any conflicts between the two.

- Run `ant` from the Floodlight root and the module is now compiled with
  Floodlight.

### Example Usage ###

Use Mininet and Open vSwitch to configure a topology of your choice and connect
the network to a Floodlight instance.

From Mininet ping one host from another and inspect the flow tables using
`ovs-ofctl dump-flows sX` to see the magic.

## External ##

The external module, written in Ruby, polls the Floodlight API for any changes
that should occur in the topology. If an update happens, the module uses a set
of API calls to find all external links and all paths between these links and
pushes proper forwarding flow entries to the corresponding switches.

This implementation resides in `src/ruby/`.

This has been tested with Ruby 2.3.0p0.
