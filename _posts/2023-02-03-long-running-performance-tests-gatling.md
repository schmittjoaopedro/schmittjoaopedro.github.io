---
layout: post
title:  "Using long running performance tests with Gatling to simulate production workloads"
date:   2023-02-03 12:00:00 +0100
categories: jekyll update
---

## Motivation

The goal here is to share some experience developing performance tests using Gatling to simulate production-like load. So we can troubleshoot application behavior and assess performance improvement gains.

The idea of these performance tests started when it was faced scalability issues for a micro-service application during an unexpected increase of requests per second, causing some requests not being responded. The main requirement was to find a way to validate if infrastructure changes would reflect in a response error ratio reduction.

Another benefit of this work was the creation of a methodology to assess performance gains when doing code and infrastructure improvements for the application, so we could quantitatively compare error reduction rates and response time improvements for various test scenarios.

## Modeling system's load on Gatling

Gatling was the chosen tool for developing these performance tests. The reason for this decision was its simple API, the efficient thread management mechanism, and the quality of the reports generated.

To model the system operation load in Gatling, we started by understanding the usual load curve observed during a normal day of operations. This information was extracted from Grafana by looking at the requests per second (RPS) metric. Below is presented a fictional example:

![Load Grafana](/assets/imgs/gatling-load-grafana.png)

With this information in hands, we modeled the observed curve to a discrete table by separating the general smooth load increases and decreases from the spikes. This table facilitates configuring Gatling through its API functions (see the snippet below the table).

| Time          | Load                      |
|---------------|---------------------------|
| 00:00 - 07:00 | 20 req/sec - 60 req/sec   |
| 07:00 - 18:00 | 60 req/sec - 60 req/sec   |
| 18:00 - 00:00 | 60 req/sec - 20 req/sec   |
| 04:00 - 04:05 | Spike +5 req/sec          |
| 05:00 - 05:05 | Spike +10 req/sec         |
| 06:00 - 06:05 | Spike +20 req/sec         |
| 07:00 - 07:05 | Spike +25 req/sec         |

```scala
val getSmooth =
    scenario("Get Smooth")
      .exec(getRequestHttp)

val getPeaks =
    scenario("Get Peaks")
      .exec(getRequestHttp)

setUp(
    // Smooth load curve
    getSmooth.inject( 
      // 00:00 -> 07:00
      rampUsersPerSec(scaleLoad(20)).to(scaleLoad(60)).during(scaleTime(7.hours)),
      // 07:00 -> 18:00
      constantUsersPerSec(scaleLoad(60)).during(scaleTime(11.hours)),
      // 18:00 -> 00:00
      rampUsersPerSec(scaleLoad(60)).to(scaleLoad(20)).during(scaleTime(6.hours))
    ),
    // Peaks
    getPeaks.inject(
      // 00:00 -> 04:00
      nothingFor(scaleTime(4.hours)),
      // 04:00 -> 04:05
      constantUsersPerSec(scaleLoad(5)).during(scaleTime(5.minutes)),
      // 04:05 -> 05:00
      nothingFor(scaleTime(55.minutes)),
      // 05:00 -> 05:05
      constantUsersPerSec(scaleLoad(10)).during(scaleTime(5.minutes)),
      // 05:05 -> 06:00
      nothingFor(scaleTime(55.minutes)),
      // 06:00 -> 06:05
      constantUsersPerSec(scaleLoad(20)).during(scaleTime(5.minutes)),
      // 06:05 -> 07:00
      nothingFor(scaleTime(55.minutes)),
      // 07:00 -> 07:05
      constantUsersPerSec(scaleLoad(25)).during(scaleTime(5.minutes))
    )
)
.protocols(httpProtocol)
// We don't assert tests to prevent the pipeline from failing and not extracting the reports
```

To create a representative set of requests, we defined a feeder file with 20 different combinations of parameters and passed it into the `getRequest` method. This strategy forces requests to pass randomly through various parts of the source code, avoiding the service from optimizing for a single shape of request. We extracted these different combinations by sampling requests from Kibana logs.

```scala
val feederGetRequest = csv("request_variations.csv").random

val getRequestHttp = feed(feederGetRequest)
    .exec(
      http("Get Request")
        .get("/endpoint")
        .queryParamMap(Map(
          "query1" -> "#{val1}",
          "query2" -> "#{val2}"
        ))
        .headers(Map(
          "header1" -> "#{val3}",
          "header2" -> "#{val4}"
        ))
        .check(status.in(200, 422))
    )
```

A drawback of the current Gatling feeder solution is that we can't have optional parameters in a feeder. For example, if you want `header2` to be optional by setting empty values in the CSV file and not sending it as part of the request, it's not possible. It means that your service has to be able to ignore empty headers. Otherwise, it'll incur extra complexity for designing your test cases (see [SO question](https://stackoverflow.com/questions/74960360/how-to-avoid-sending-null-headers-in-gatling-http-request)).

As you might have noticed, the above code used two functions `scaleLoad` and `scaleTime` when setting up the simulation. These functions work in pair with the below properties:

```scala
val durationMinutes = System.getProperty("durationMinutes", "60")
val loadFactor = System.getProperty("loadFactor", "1")
```

These properties enable you to run tests in different setups. For example, you can run the same load scenario for 1 hour or 24 hours by passing those values in minutes through parameters like: `--define durationMinutes=60`. You can vary the test load by multiplying the scenario configuration by a factor. For example, to run the same scenario with twice the load you have to set the parameter `--define loadFactor=2.0` when running the program. The source code in Gatling used to scale these values is provided below:

```scala
def scaleTime(time: FiniteDuration): FiniteDuration = {
  val daySeconds: Double = 86400.0
  val durationSeconds: Double = new FiniteDuration(durationMinutes.toLong, TimeUnit.MINUTES).toSeconds.toDouble
  val unscaledTimeSeconds: Double = time.toSeconds.toDouble
  val scaledTimeSeconds: Double = (durationSeconds / daySeconds) * unscaledTimeSeconds
  val newTime = new FiniteDuration(scaledTimeSeconds.toLong, TimeUnit.SECONDS)
  newTime
}

def scaleLoad(load: Int): Int = {
  val factor: Double = loadFactor.toDouble
  val newLoad = (load * factor).ceil.toInt
  newLoad
}
```

* Test environment

## Setting up execution on Jenkins

* Benefits of running Jenkins
* Running Gatling from POD on Kubernetes EKS (latency reduction)
* Max Load Factor supported observed on Gatling 
* Future improvements, start various parallel PODs for certain limits
* Data collection and storage on S3

## Use Case 1: Optimizing Kubernetes Deployment paramenters

* Isolating variables
* Modeling combinations
* Defining metrics to collect
* Results observed
* Re-execution with best combinations
* Final outcomes (samples of reports)

## Conclusions