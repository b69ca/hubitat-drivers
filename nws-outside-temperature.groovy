/**
 *
 *  ****************  NWS Outside Temperature Sensor  ****************
 *
 *  importUrl: https://raw.githubusercontent.com/b69ca/hubitat-drivers/main/nws-outside-temperature.groovy
 *
 *  Copyright 2025 Jon Wallace
 *
 *  LICENSE:
 *
 *  MIT License
 *
 *  Copyright (c) 2025 Jon Wallace
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 *
 */

import groovy.json.JsonSlurper

metadata {
  definition (
    name: "NWS Outside Temperature Sensor",
    namespace: "jonw",
    author: "Jon Wallace"
  ) {
    capability "TemperatureMeasurement"
    capability "Sensor"
    capability "Refresh"

    command "getOutsideTemperature"
  }

  preferences {
    input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
    input name: "updateFrequency", type: "number", title: "Auto-Refresh Interval (minutes)", defaultValue: 30, range: "1..1440"
    input name: "minChange", type: "number", title: "Minimum Temperature Change to Send Event (°F)", defaultValue: 0.1, range: "0.01..100"
  }
}

def installed() {
  log.info "Installed NWS Outside Temperature Sensor"
  initialize()
}

def updated() {
  log.info "Updated settings"
  initialize()
}

def initialize() {
  unschedule()
  scheduleAutoRefresh()
  getOutsideTemperature()
}

def refresh() {
  getOutsideTemperature()
}

def scheduleAutoRefresh() {
  def minutes = settings?.updateFrequency ?: 30
  minutes = Math.max(1, Math.min(1440, minutes)) // enforce valid bounds

  if (logEnable) log.debug "Scheduling auto-refresh every ${minutes} minutes"

  schedule("0 */${minutes} * ? * *", getOutsideTemperature)
}

def getOutsideTemperature() {
  def coords = getHubCoordinates()
  if (!coords) {
    log.error "Hub coordinates not set"
    return
  }

  def url = "https://api.weather.gov/points/${coords.lat},${coords.lon}"

  httpGet([uri: url, headers: [ "User-Agent": "Hubitat NWS Driver" ]]) { resp ->
    if (resp.status == 200) {
      def properties = new JsonSlurper().parseText(resp.data.text)?.properties
      def stationUrl = properties?.observationStations

      if (stationUrl) {
        getLatestObservation(stationUrl)
      } else {
        log.warn "No observation station URL found"
      }
    } else {
      log.error "Failed to get station info: ${resp.status}"
    }
  }
}

def getLatestObservation(stationCollectionUrl) {
  httpGet([uri: stationCollectionUrl, headers: [ "User-Agent": "Hubitat NWS Driver" ]]) { resp ->
    if (resp.status == 200) {
      def stations = new JsonSlurper().parseText(resp.data.text)?.features
      if (!stations || stations.size() == 0) {
        log.warn "No stations found in collection"
        return
      }

      def stationId = stations[0]?.properties?.stationIdentifier
      if (stationId) {
        getObservationForStation(stationId)
      } else {
        log.warn "Station ID not found"
      }
    } else {
      log.error "Failed to get stations: ${resp.status}"
    }
  }
}

def getObservationForStation(String stationId) {
  def url = "https://api.weather.gov/stations/${stationId}/observations/latest"

  httpGet([uri: url, headers: [ "User-Agent": "Hubitat NWS Driver" ]]) { resp ->
    if (resp.status == 200) {
      def obs = new JsonSlurper().parseText(resp.data.text)?.properties
      def tempC = obs?.temperature?.value
      if (tempC != null) {
        def tempF = ((tempC * 9 / 5) + 32).setScale(1, BigDecimal.ROUND_HALF_UP)
        def currentTemp = device.currentValue("temperature") as BigDecimal
        def threshold = (settings?.minChange ?: 0.1) as BigDecimal

        if (currentTemp == null || (tempF - currentTemp).abs() >= threshold) {
          sendEvent(name: "temperature", value: tempF, unit: "°F")
          if (logEnable) log.debug "Temperature updated to ${tempF}°F"
        } else if (logEnable) {
          log.debug "Temperature change (${tempF}°F) within threshold (${threshold}°F); event not sent."
        }
      } else {
        log.warn "Temperature not found in observation"
      }
    } else {
      log.error "Failed to get observation for station ${stationId}: ${resp.status}"
    }
  }
}

def getHubCoordinates() {
  def lat = location?.latitude
  def lon = location?.longitude
  return (lat && lon) ? [lat: lat, lon: lon] : null
}
