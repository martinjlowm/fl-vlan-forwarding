#!/usr/bin/env ruby

require 'net/http'
require 'json'

require './restful'

quit = false
trap("INT") do
  quit = true
end

class VLANForwarding < RESTful
  attr_accessor :http, :topology, :vlanID

  def initialize(srv)
    super(srv)
    @topology = []
    @vlanID = 2
  end

  def check_topology
    Net::HTTP.start(@server.host, @server.port) do |http|
      @http = http

      req = GET('wm/topology/links')
      resp = @http.request req
      topology = JSON[resp.body]

      # The order differs. Just compare the length and only update if new links
      # are added or removed
      break unless @topology.length != topology.length

      @topology = topology

      req = GET("wm/staticflowpusher/clear/all")
      resp = @http.request req

      req = GET('wm/core/controller/switches')
      resp = @http.request req
      switches = JSON[resp.body].sort_by { |e| e['switchDPID'] }

      unknownLinks = []
      switches.each do |switch|
        switchID = switch['switchDPID']
        req = GET("wm/core/switch/#{switchID}/port")
        resp = @http.request req

        ports = JSON.parse(resp.body)

        ports['port_reply'][0]['port'].each do |port|
          portNumber = port['portNumber']

          unless portNumber == 'local'
            knownLink = topology.select { |link|
              (link['src-switch'] == switchID && link['src-port'] == portNumber.to_i) ||
                (link['dst-switch'] == switchID && link['dst-port'] == portNumber.to_i)
            }

            if knownLink.empty?
              unknownLinks << {switch: switchID, port: portNumber}
            end
          end
        end
      end

      unknownLinks.each_with_index do |src, idx|
        unknownLinks.drop(idx + 1).each do |dst|
          add_route(src, dst)
        end
      end
    end
  end

  def add_route(src, dst)
    req = GET("wm/topology/route/#{src[:switch]}/#{src[:port]}/#{dst[:switch]}/#{dst[:port]}")
    resp = @http.request req
    route = JSON[resp.body]

    srcNet = src[:switch][-2..-1].to_i << 4 | src[:port].to_i
    dstNet = dst[:switch][-2..-1].to_i << 4 | dst[:port].to_i

    numSwitchInterfaces = route.length

    i = numSwitchInterfaces - 1
    route.reverse.each_slice(2) do |inface, outface|
      isStartEdgeSwitch = 1 == i
      isEndEdgeSwitch = numSwitchInterfaces - 1 == i

      flowName = "VLAN_#{inface['switch']}_#{srcNet}_#{dstNet}"
      flowNameReverse = "REVERSE_" + flowName;

      build_flow(inface['switch'], flowNameReverse,
                 outface['port']['portNumber'], inface['port']['portNumber'],
 		 isStartEdgeSwitch, isEndEdgeSwitch, false,
		 srcNet, dstNet, numSwitchInterfaces == 2)
      build_flow(inface['switch'], flowName,
                 inface['port']['portNumber'], outface['port']['portNumber'],
 		 isStartEdgeSwitch, isEndEdgeSwitch, true,
		 srcNet, dstNet, numSwitchInterfaces == 2)

      i = i - 2
    end

    @vlanID = @vlanID % 0x0FFF + 1
    @vlanID = @vlanID + (@vlanID == 1 ? 1 : 0)
  end

  def build_flow(sw, flowName, inPort, outPort,
                 isStartEdgeSwitch, isEndEdgeSwitch, isReverse,
                 srcNet, dstNet, isSingleSwitch)
    flowHash = {}

    flowHash['switch'] = sw
    flowHash['name'] = flowName
    flowHash['cookie'] = '0'
    flowHash['priority'] = '1'
    flowHash['in_port'] = inPort

    flowHashARP = flowHash.clone
    flowHashARP['name'] = "#{flowHashARP['name']}_ARP"
    actions = []

    if isStartEdgeSwitch || isEndEdgeSwitch then
      unless isSingleSwitch then
        if (isStartEdgeSwitch && !isReverse) ||
	   (isEndEdgeSwitch && isReverse) then
          actions << 'push_vlan=0x8100'
          actions << "set_field=eth_vlan_vid->#{@vlanID}"
          flowHash['eth_type'] = '0x0800'
        else
          flowHash['eth_vlan_vid'] = (0x1000 | @vlanID).to_s
          actions << 'pop_vlan'
        end
      else
        flowHash['eth_type'] = '0x0800'
      end

      flowHashARP['eth_type'] = '0x0806'

      if isStartEdgeSwitch && !isReverse then
        flowHash['ipv4_dst'] = "10.0.#{dstNet}.0/24"
        flowHashARP['arp_tpa'] = "10.0.#{dstNet}.0/24"
      elsif isEndEdgeSwitch && isReverse then
        flowHash['ipv4_dst'] = "10.0.#{srcNet}.0/24"
        flowHashARP['arp_tpa'] = "10.0.#{srcNet}.0/24"
      end
    else
      flowHash['eth_vlan_vid'] = (0x1000 | @vlanID).to_s
    end

    actions << "output=#{outPort}"

    flowHash['instruction_apply_actions'] = actions.join(',')
    flowHashARP['instruction_apply_actions'] = flowHash['instruction_apply_actions']

    puts "#{sw} #{isStartEdgeSwitch}<->#{isEndEdgeSwitch} #{inPort}:#{outPort} -> #{flowHash}"

    req = POST("wm/staticflowpusher", flowHash)
    resp = @http.request req
    puts JSON[resp.body]

    if (isStartEdgeSwitch && !isReverse) ||
       (isEndEdgeSwitch && isReverse) then
      req = POST("wm/staticflowpusher", flowHashARP)
      resp = @http.request req
      # puts JSON[resp.body]
    end

  end
end

forwarding = VLANForwarding.new('192.168.57.1:8080')

until quit do
  forwarding.check_topology

  sleep(2)
end
