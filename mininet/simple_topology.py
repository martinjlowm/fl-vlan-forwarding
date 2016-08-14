#!/usr/bin/python

"""Builds a topology set-up with 3 edge switches as illustrated in
the following.

     h2        h3
       \       |
        s2 -- s4 -- h4
       /      /  \
h1 -- s1 -- s3    s5
        \  /  \  /
         s7    s6

"""

import os

from mininet.net import Mininet
from mininet.node import OVSSwitch, Controller, RemoteController
from mininet.topolib import Topo
from mininet.log import setLogLevel
from mininet.cli import CLI
from mininet.link import TCLink
from mininet.util import pmonitor

setLogLevel('info')

class SimpleTopology(Topo):
    def __init__(self, **opts):
        Topo.__init__(self, **opts)

        # Edge switches
        s1 = self.addSwitch('s1', dpid='0000000000000001')
        s2 = self.addSwitch('s2', dpid='0000000000000002')
        s4 = self.addSwitch('s4', dpid='0000000000000004')

        # Switches
        s3 = self.addSwitch('s3', dpid='0000000000000003')
        s5 = self.addSwitch('s5', dpid='0000000000000005')
        s6 = self.addSwitch('s6', dpid='0000000000000006')
        s7 = self.addSwitch('s7', dpid='0000000000000007')

        # Hosts
        h1 = self.addHost('h1', ip = ('10.0.%s.1' % (1 << 4 | 1)))
        h2 = self.addHost('h2', ip = ('10.0.%s.1' % (2 << 4 | 1)))
        h3 = self.addHost('h3', ip = ('10.0.%s.1' % (4 << 4 | 1)))
        h4 = self.addHost('h4', ip = ('10.0.%s.1' % (4 << 4 | 2)))

        # Host links
        self.addLink(h1, s1)
        self.addLink(h2, s2)
        self.addLink(h3, s4)
        self.addLink(h4, s4)

        # Switch links
        self.addLink(s1, s7)
        self.addLink(s1, s2)
        self.addLink(s1, s3)
        self.addLink(s2, s4)
        self.addLink(s7, s3)
        self.addLink(s3, s6)
        self.addLink(s6, s5)
        self.addLink(s4, s5)
        self.addLink(s3, s4)

topo = SimpleTopology()
net = Mininet(topo = topo, link = TCLink, build = False, autoSetMacs = True)

c0 = RemoteController( 'c0', ip = '192.168.57.1', port = 6653 )
net.addController(c0)
net.build()
net.start()

CLI(net)

# Done
net.stop()
