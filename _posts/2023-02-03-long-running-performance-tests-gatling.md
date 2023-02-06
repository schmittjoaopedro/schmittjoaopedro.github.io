---
layout: post
title:  "Simulating production workloads by long running performance tests with Gatling"
date:   2023-02-03 12:00:00 +0100
categories: jekyll update
---

## Motivation

The goal of this article is to share some of my experience developing performance tests using Gatling to simulate production-like behavior to troubleshoot application behavior and also assess performance improvement gains.

The idea of these performance tests started when it was spotted scalability issues for a Quarkus micro-service application during an unexpected increase of requests per second. The unexpected load caused requests to be dropped, and the requirement was to find a way to proactively prevent those incidents by being prepared in certain level for an increase load on the service.

Another benefit of this work was to be able to assess performance gains when doing code and infrastructure improvements for the application, by quantitatively comparing the error reduction rate and response time improvements for our test scenarios.

## Modeling production load on Gatling

* Gatling
* Example samples extracted from logs
* Difficult to handle optional variables
* Steady requests per second modelling during the day
* Spikes modeling during specific times
* Load Factor
* Duration
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