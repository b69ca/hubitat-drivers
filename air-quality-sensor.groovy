/**
 *
 *  ****************  Air Quality Sensor (Open-Meteo)  ****************
 *
 *  importUrl: https://raw.githubusercontent.com/b69ca/hubitat-drivers/main/air-quality-sensor.groovy
 *
 *  Copyright 2025 Jon Wallace
 *
 *  LICENSE: MIT
 *
 */

import groovy.json.JsonSlurper

metadata {
  definition (
    name: "Air Quality Sensor",
    namespace: "jonw",
    author: "Jon Wallace"
  ) {
    capability "Sensor"
    capability "Refresh"

    attribute "aqiPM25", "number"
    attribute "aqiPM10", "number"
    attribute "aqiNO2", "number"
    attribute "aqiO3", "number"
    attribute "aqiCO", "number"
    attribute "aqiSO2", "number"
    attribute "airQualityStatus", "string"

    command "getAirQuality"
  }

  preferences {
    input name: "refreshInterval", type: "number", title: "Auto-Refresh Interval (minutes)", defaultValue: 180, range: "5..1440"
    input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
  }
}

def installed() {
  log.info "Installed Air Quality Sensor"
  initialize()
}

def updated() {
  log.info "Updated Air Quality Sensor settings"
  initialize()
}

def initialize() {
  unschedule()
  scheduleRefresh()
  getAirQuality()
}

def refresh() {
  getAirQuality()
}

def scheduleRefresh() {
  def minutes = settings?.refreshInterval ?: 180
  minutes = Math.max(5, Math.min(1440, minutes))
  if (logEnable) log.debug "Scheduling AQ refresh in ${minutes} minutes"
  runIn(minutes * 60, "getAirQuality")
}

def getAirQuality() {
  def lat = location?.latitude
  def lon = location?.longitude
  if (!lat || !lon) {
    log.error "Hub location coordinates not set"
    return
  }

  def url = "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=${lat}&longitude=${lon}&hourly=pm10,pm2_5,carbon_monoxide,nitrogen_dioxide,ozone,sulphur_dioxide&timezone=auto"

  try {
    httpGet([uri: url, contentType: "application/json"]) { resp ->
      if (resp.status == 200) {
        def data = resp.data
        def latestHour = data?.hourly?.time?.size() - 1
        if (latestHour < 0) {
          log.warn "No hourly air quality data available"
          return
        }

        def setAttr = { name, list ->
          def value = list ? list[latestHour] : null
          if (value != null) {
            sendEvent(name: name, value: value)
            if (logEnable) log.debug "${name} = ${value}"
          }
        }

        setAttr("aqiPM10", data.hourly.pm10)
        setAttr("aqiPM25", data.hourly.pm2_5)
        setAttr("aqiCO", data.hourly.carbon_monoxide)
        setAttr("aqiNO2", data.hourly.nitrogen_dioxide)
        setAttr("aqiO3", data.hourly.ozone)
        setAttr("aqiSO2", data.hourly.sulphur_dioxide)

        def pm25 = data.hourly.pm2_5[latestHour] ?: 0
        def status = classifyPM25(pm25)
        sendEvent(name: "airQualityStatus", value: status)
        if (logEnable) log.debug "Air Quality Status = ${status}"
      } else {
        log.error "Failed to fetch air quality data: ${resp.status}"
      }
    }
  } catch (Exception e) {
    log.error "Error fetching air quality: ${e.message}"
  }

  scheduleRefresh()
}

def classifyPM25(pm25) {
  def v = pm25 as double
  if (v <= 12) return "Good"
  if (v <= 35.4) return "Moderate"
  if (v <= 55.4) return "Unhealthy for Sensitive Groups"
  if (v <= 150.4) return "Unhealthy"
  if (v <= 250.4) return "Very Unhealthy"
  return "Hazardous"
}
