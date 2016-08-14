require 'net/http'
require 'json'

class RESTful
  attr_accessor :server

  def initialize(srv)
    @server = URI("http://#{srv}")
  end

  def GET(uri)
    rest_call(Net::HTTP::Get, uri)
  end

  def POST(uri, data)
    rest_call(Net::HTTP::Post, uri, data)
  end

  def PUT(uri)
    rest_call(Net::HTTP::Put, uri)
  end

  def DELETE(uri)
    rest_call(Net::HTTP::Delete, uri)
  end

  def urlify(relative_url)
    URI("#{@server}/#{relative_url}/json")
  end

  def rest_call(type, uri, data = nil)
    req = type.new(urlify(uri))

    req['Content-type'] = 'application/json'
    req['Accept'] = 'application/json'

    req.body = data.to_json
    req
  end
end
