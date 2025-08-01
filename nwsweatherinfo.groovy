/**
 *
 *  ****************  NWS Weather Driver  ****************
 *
 *  importUrl: https://raw.githubusercontent.com/b69ca/hubitat-drivers/main/nwsweatherinfo.groovy
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
    name: "NWS Weather Info",
    namespace: "jonw",
    author: "Jon Wallace"
  ) {
    capability "Actuator"
    capability "Sensor"
    capability "Refresh"

    command "getWeatherInfo"
    command "clearForecastCache"

    attribute "weatherSummaryString", "string"
    attribute "lastUpdated", "string"

    ["forecastPeriod1", "forecastPeriod2"].each { i ->
      attribute "${i}Name", "string"
      attribute "${i}Start", "string"
      attribute "${i}End", "string"
      attribute "${i}IsDay", "string"
      attribute "${i}Temperature", "number"
      attribute "${i}Unit", "string"
      attribute "${i}Wind", "string"
      attribute "${i}Direction", "string"
      attribute "${i}Summary", "string"
      attribute "${i}Detail", "string"
    }
  }

  preferences() {
  }
}

def installed() {
  log.info("Weather info - installed")
  initialize()
  getWeatherInfo()
}

def updated() {
  log.info("Weather info - updated")
  initialize()
  getWeatherInfo()
}

def initialize() {
  // No-op for now
}

def refresh() {
  log.info("Weather info - refresh data")
  getWeatherInfo()
}

def getWeatherInfo() {
  getForecastString { summaryData ->
    def now = new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone)

    if (summaryData?.readable) {
      log.info("Weather Summary: ${summaryData.readable}")

      sendEvent(name: "weatherSummaryString", value: summaryData.readable)
      sendEvent(name: "lastUpdated", value: now)

      ["first", "second"].eachWithIndex { label, index ->
        def period = summaryData[label]
        def num = index + 1

        sendEvent(name: "forecastPeriod${num}Name", value: period.name)
        sendEvent(name: "forecastPeriod${num}Start", value: period.start)
        sendEvent(name: "forecastPeriod${num}End", value: period.end)
        sendEvent(name: "forecastPeriod${num}IsDay", value: period.isDay)
        sendEvent(name: "forecastPeriod${num}Temperature", value: period.temperature)
        sendEvent(name: "forecastPeriod${num}Unit", value: period.unit)
        sendEvent(name: "forecastPeriod${num}Wind", value: period.wind)
        sendEvent(name: "forecastPeriod${num}Direction", value: period.direction)
        sendEvent(name: "forecastPeriod${num}Summary", value: period.summary)
        sendEvent(name: "forecastPeriod${num}Detail", value: period.detail)
      }

    } else {
      def fallback = "No weather information is available right now..."
      log.warn(fallback)

      sendEvent(name: "weatherSummaryString", value: fallback)
      sendEvent(name: "lastUpdated", value: now)

      ["forecastPeriod1", "forecastPeriod2"].each { i ->
        ["Name", "Start", "End", "IsDay", "Temperature", "Unit", "Wind", "Direction", "Summary", "Detail"].each {
          sendEvent(name: "${i}${it}", value: "")
        }
      }
    }
  }
}

def clearForecastCache() {
  log.info("Clearing cached forecast URL and coordinates...")
  state.remove("cachedForecastUrl")
  state.remove("cachedLat")
  state.remove("cachedLon")
  log.info("Forecast cache cleared.")
}

def getHubCoordinates() {
  def lat = location.latitude
  def lon = location.longitude

  if (lat && lon) {
    return [lat: lat, lon: lon]
  } else {
    return null
  }
}

def getForecastUrl(Closure callback) {
  def coordinates = getHubCoordinates()

  if (!coordinates) {
    log.error("Hub coordinates have not been set - set the coordinates in the hub settings.")
    callback(null)
    return
  }

  def lat = coordinates.lat
  def lon = coordinates.lon

  if (
    state.cachedForecastUrl &&
    state.cachedLat == lat &&
    state.cachedLon == lon
  ) {
    log.debug("Using cached forecast URL.")
    callback(state.cachedForecastUrl)
    return
  }

  def url = "https://api.weather.gov/points/${lat},${lon}"

  httpGet(url) { response ->
    if (response.status == 200) {
      def data = new JsonSlurper().parseText(response.data.text)
      def forecastUrl = data?.properties?.forecast
      if (forecastUrl) {
        log.debug("Fetched new forecast URL and updated cache.")
        state.cachedForecastUrl = forecastUrl
        state.cachedLat = lat
        state.cachedLon = lon
        callback(forecastUrl)
      } else {
        log.warn("Forecast URL not found in response.")
        callback(null)
      }
    } else {
      log.error("Failed to retrieve forecast URL. Status: ${response.status}")
      callback(null)
    }
  }
}

def getForecastData(Closure callback) {
  getForecastUrl { forecastUrl -> 
    if (!forecastUrl) {
      callback(null)
      return
    }

    httpGet(forecastUrl) { response ->
      if (response.status == 200) {
        def data = new JsonSlurper().parseText(response.data.text)
        def periods = data?.properties?.periods

        if (periods?.size() >= 2) {
          def sanitize = { v -> v != null ? v.toString() : "unknown" }

          def forecast = [
            first: [
              name: sanitize(periods[0].name),
              start: sanitize(periods[0].startTime),
              end: sanitize(periods[0].endTime),
              isDay: periods[0].isDaytime?.toString(),
              temperature: periods[0].temperature ?: 0,
              unit: sanitize(periods[0].temperatureUnit),
              wind: sanitize(periods[0].windSpeed),
              direction: sanitize(periods[0].windDirection),
              summary: sanitize(periods[0].shortForecast),
              detail: sanitize(periods[0].detailedForecast)
            ],
            second: [
              name: sanitize(periods[1].name),
              start: sanitize(periods[1].startTime),
              end: sanitize(periods[1].endTime),
              isDay: periods[1].isDaytime?.toString(),
              temperature: periods[1].temperature ?: 0,
              unit: sanitize(periods[1].temperatureUnit),
              wind: sanitize(periods[1].windSpeed),
              direction: sanitize(periods[1].windDirection),
              summary: sanitize(periods[1].shortForecast),
              detail: sanitize(periods[1].detailedForecast)
            ]
          ]
          callback(forecast)
        } else {
          log.warn("Not enough forecast data received.")
          callback(null)
        }
      } else {
        log.error("Failed to retrieve forecast data. Status: ${response.status}")
        callback(null)
      }
    }
  }
}

def getForecastString(Closure callback) {
  getForecastData { forecast ->
    if (forecast) {
      def capitalize = { str ->
        if (!str) return ""
        return str[0].toUpperCase() + str.substring(1)
      }

      def formatPeriod = { period ->
        def name = capitalize(period.name)
        def temp = "${period.temperature}°${period.unit}"
        def dir = expandDirection(period.direction)
        def windText = (period.wind?.toLowerCase() != "calm" && period.wind) ? " Winds ${period.wind} from the ${dir}." : ""
        def shortForecast = period.summary ?: "No summary"
        def detail = period.detail ?: ""

        return "${name}: ${shortForecast}. Temperature around ${temp}.${windText} ${detail}".trim()
      }

      def first = formatPeriod(forecast.first)
      def second = formatPeriod(forecast.second)

      def combined = "${first} ${second}"
      callback([
        readable: combined,
        first: forecast.first,
        second: forecast.second
      ])
    } else {
      callback(null)
    }
  }
}

def expandDirection(abbr) {
  def map = [
    N: "north", NNE: "north-northeast", NE: "northeast", ENE: "east-northeast",
    E: "east", ESE: "east-southeast", SE: "southeast", SSE: "south-southeast",
    S: "south", SSW: "south-southwest", SW: "southwest", WSW: "west-southwest",
    W: "west", WNW: "west-northwest", NW: "northwest", NNW: "north-northwest"
  ]
  return map[abbr?.toUpperCase()] ?: abbr?.toLowerCase()
}
