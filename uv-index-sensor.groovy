/**
 *
 *  ****************  UV Index Sensor  ****************
 *
 *  importUrl: https://raw.githubusercontent.com/b69ca/hubitat-drivers/main/uv-index-sensor.groovy
 *
 *  Copyright 2025 Jon Wallace
 *
 *  LICENSE: MIT
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
    name: "UV Index Sensor",
    namespace: "jonw",
    author: "Jon Wallace"
  ) {
    capability "Sensor"
    capability "Refresh"

    attribute "uvIndex", "number"
    attribute "uvRiskLevel", "string"

    command "getUVIndex"
  }

  preferences {
    input name: "zipcode", type: "text", title: "Zip Code", required: true
    input name: "refreshInterval", type: "number", title: "Auto-Refresh Interval (minutes)", defaultValue: 60, range: "5..1440"
    input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
  }
}

def installed() {
  log.info "Installed UV Index Sensor"
  initialize()
}

def updated() {
  log.info "Updated UV Index Sensor settings"
  initialize()
}

def initialize() {
  unschedule()
  scheduleUVRefresh()
  getUVIndex()
}

def refresh() {
  getUVIndex()
}

def scheduleUVRefresh() {
  def minutes = settings?.refreshInterval ?: 60
  minutes = Math.max(5, Math.min(1440, minutes))
  if (logEnable) log.debug "Scheduling UV index refresh in ${minutes} minutes"
  runIn(minutes * 60, "getUVIndex")
}

def getUVIndex() {
  if (!zipcode) {
    log.warn "Zip code not configured"
    return
  }

  def apiUrl = "https://enviro.epa.gov/enviro/efservice/getEnvirofactsUVDAILY/ZIP/${zipcode}/JSON"

  try {
    httpGet([uri: apiUrl, contentType: "application/json"]) { resp ->
      if (resp.status == 200) {
        def data = resp.data
        if (!data || data.size() == 0) {
          log.warn "No UV index data returned"
          return
        }

        def today = data[0]
        def uv = today?.UV_INDEX?.toBigDecimal()?.setScale(1, BigDecimal.ROUND_HALF_UP)
        def level = getUVRiskLevel(uv)

        sendEvent(name: "uvIndex", value: uv)
        sendEvent(name: "uvRiskLevel", value: level)

        if (logEnable) log.debug "UV Index is ${uv} (${level})"
      } else {
        log.error "Failed to fetch UV index: ${resp.status}"
      }
    }
  } catch (Exception e) {
    log.error "Error fetching UV Index: ${e.message}"
  }

  scheduleUVRefresh()
}

def getUVRiskLevel(BigDecimal uv) {
  if (uv < 3) return "Low"
  if (uv < 6) return "Moderate"
  if (uv < 8) return "High"
  if (uv < 11) return "Very High"
  return "Extreme"
}
