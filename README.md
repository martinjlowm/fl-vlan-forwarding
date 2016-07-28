# Floodlight Module: VLAN Forwarding (internal and external) #

VLAN identifiers are incremented for each unique route and resets to 0x02 when the
maximum value is reached. 0x00 and 0xFF are reserved.

## Internal ##
Listens to topology changes and builds a VLAN route to a destination network.

Each destination network belongs to a subnet of where the third byte is
constructed as follows.

Edge switches' identifiers must be unique and be representable by 4 bits. These
4 bits make the upper subnet identifier and the lower 4 bits is given by the
switch's ports.

## External ##
Flow entries for each route are pushed as static entries to the switches and
forwarding is based solely on VLAN tags. The external module uses Floodlight's
REST API.
