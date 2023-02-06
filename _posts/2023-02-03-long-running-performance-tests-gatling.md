---
layout: post
title:  "Simulating production workloads by long running performance tests with Gatling"
date:   2023-02-03 12:00:00 +0100
categories: jekyll update
---

## Motivation

* Problem
  * Production spikes
  * Incidents
* Goal
  * Simulate production workloads
  * Framework to assess the impact of new changes
  * Estimate performance gains

## Gatling

## Modeling production load on Gatling

* Example samples extracted from logs
* Difficult to handle optional variables
* Steady requests per second modelling during the day
* Spikes modeling during specific times
* Load Factor
* Duration

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