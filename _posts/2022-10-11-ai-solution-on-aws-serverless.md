---
layout: post
title:  "Architecting an AI solution on AWS serverless"
date:   2022-10-10 12:00:00 +0100
categories: jekyll update
---
{% include math-formatter.html %}

## Motivation

My motivation with this article is three fold.
First, I want to share my experience on developing an AI system using only AWS serverless components.
Secondly, I want to share the general performance of AWS Lambda in terms of running the CPU-intensive AI solver in Quarkus Native.
Finally, I want to share my experience in developing all the infrastructure as code using CloudFormation and automating all CI/CD with Github Actions.

## Vehicle Routing Problem

The vehicle routing problem (VRP) is a combinatorial optimization problem which asks "What is the optimal set of routes for a fleet of vehicles to traverse in order to deliver goods to a given set of customers?" $^1$. 
In general the VRP shows up in real scenarios like those challenged by logistic companies (DHL, Kuehne + Nagel, Fedex), delivery apps (Uber Eats, Glovo), and dial-a-ride companies (Uder, Taxi99, Bolt).

For example, take a look at the image below to understand how it works.
On the left there's the problem, a set of customers spread across the map that have requested goods to be delivered from a company's depot.
On the right there's the solution for the problem, it consists of a set of routes (a.k.a. itineraries) assigned to a fleet of vehicles that will deliver the goods to the customers on time.
The VRP problem's challenge is about how to find the best itineraries to minimize (or maximize) a specific KPI (e.g.: total travel time, total gas consumption, total delays, profit, etc) $^2$.

![vehicle Routing Problem Example](/assets/imgs/vehicle-routing-problem.png)

### Why is Vehicle Routing Problem hard solve?

Imagine you want to build all possible words using only three letters A, B, and R.
In this scenario you would permutate ABC in all possible ways (ABR, ARB, BAR, BRA, RAB, and RBA) and pick only valid words (BAR).
Now, as more letters as you add, more possible combinations you have.
The number of combinations grow exponentially as you add more letters.
For example, if we try to build all valid words using 26 letters, we would have 403291461126605635584000000 different words.
Suppose the computer takes 1 nanosecond to build and validate each word, in this scenario it would require at least 127882883 centuries to finish the whole task.
The thing about the VRP is that it can be framed in the same model of the word building example, it's just a madder of facing each letter as a customer, and each word as a possible itinerary solution, by doing that you can see the problem gets intractable in terms of finding the best solution for the problem in a one's lifetime.

There are many ways to tackle this problem, the most common ones are the application of exact and heuristic algorithms. 
Exact algorithms try to build all possible solutions by avoiding combinations that lead to invalid solutions in a way to save time.
Heuristic algorithms use contextual knowledge to guide the algorithm on building promissing solutions that will solve the problem, sometimes building invalid solutions.
Depending on how many "valid" solutions you have, one method can be better than the other, for problems with high number of "valid" solutions heuristics tend to perform better.

For many reasons not discussed in this article, it was chosen to implement an AI heuristic solver based on the Adaptive-Large Neighborhood Search method (ALNS for short).
There were many others available by the time it was decided on this one, like Genetic Algorithms, Swarm Optimization, Tabu Search, etc.
For the sake of this article, we'll refer to the ALNS AI implementation as "solver".
Solvers in general are CPU-intensive tasks, once the problem is loaded in memory, solvers do lots of math operations to search for optimal solutions.
Therefore, this CPU-intensive tasks is one of motivations for this article, the idea is to test how AWS serverless components can perform on this kind workloads.

## Logical Design

This section presents the logical flow for the solution before we dig deep into the technical details. 
The following sequence diagram shows the common use case, a user creating a route and requesting to generate the itinerary. 
The diagram contains all microservices (the top 7 boxes) and also presents all the system interactions highlighted in red. 
The subsequent subsections discuss in detail each of the steps. 

![Logical Design](/assets/imgs/sequence-diagram.png)

### Step 1

The user logs into the application. 
Based on its credentials the `routeForm` web page is presented or not.
![Step 1](/assets/imgs/step1.png)

### Step 2

The user edits a route. 
In the `RouteWeb` the user can configure the depot params (location and attendance time window) and configure various customer requests (product pickup location, product delivery location, attendance time window, product weight, and service time). 
The service `RouteWeb` calls the `AddressService` to fetch the latitude and longitude for each location. 
When the route is saved, `RouteWeb` calls the `RouteService` to persist the route in the database.
![Step 2](/assets/imgs/step2.png)

### Step 3

The user clicks on "Generate Itinerary" so that `RouteWeb` calls the `RouteService` to request the generation.
This process can take some time to be done, so `RouteService` gather all information recorded in Step 2 and asynchronously calls the `OrchestratorService` to generate an itinerary. 
A response is sent back to the user from `RouteService` informing the generation is _InProgress_.
![Step 3](/assets/imgs/step3.png)

### Step 4

`OrchestratorService` implements the Orchestrator design pattern and coordinates all steps to generate an itinerary. 
First, `OrchestratoService` requests the `DistanceService` to calculate the distance matrix between all locations, when it's done the result is sent back to `OrchestratorService`. 
Next, `OrchestratorService` calls the `SolverService` passing the route and the distance matrix as params to generate the itinerary, when `SolverService` is done it sends back the response to `OrchestratorService` that then notifies the `RouteService` with the final itinerary.

### Step 5

If a user requests to see the itinerary before it has been generated, a blank map is shown.

### Step 6

If the user requests to see the itinerary after it has been generated, then the itinerary details are presented in a table and also the routes are drawn on a map.
![Step 6](/assets/imgs/step6.png)

## AWS Serverless

Now that general view of how the system operates was given in the previous section, we can start digging deep into the technical details.
The serverless architecture was chosen due to its scalability and cost efficiency (pay for what you use).
However, designing for serverless requires us to take into considerations a few aspects:

- The Lambda serverless workloads are ephemeral and stateless, it means the solution must be designed in a way that it doesn't store state information in the lambda function as the Lambda's instances are killed from time to time by AWS.
- The solution cannot rely on Lambda's OS architecture, so the configuration space is limited in terms of file system, OS libs and features.
- Ideally it should focus on small services for Lambda functions, Lambda's lifetime is short and not recommended for long-running applications. It means you can't benefit much of caching internal transactional state.
- Idle CPU time in Lambda should be minimized, it's a good idea to avoid many external synchronous integrations as it puts the CPU in idle state.
When a Lambda function is idle waiting for a synchronous response new requests to the function start new Lambda instances. Hence, reducing service availability when it reaches the maximum number of parallel functions and also increasing your AWS costs by paying for idle CPU time.
- Asynchronous processing through events is generally a good fit. The function is decoupled from waiting a response promptly and gives room for new requests to come.
- In terms of security, make sure each lambda function have their own unique role so the principle of the least privilege is followed.
- In terms of performance, one of the most important metrics is the startup time of the service to prevent cold start issues.

### Serverless components

For each microservice, the following diagram presents how the services are organized and which components were used for each one.

![Serverless architecture](/assets/imgs/serverless-architecture.png)

For the `RouteWeb` it was chosen the S3 and CloudFront as the solution to enable fine-grained control about the components. 
As development framework it was decided to use React and AWS Amplifier libs.

For the `AuthorizationService` it was used `Cognito` because it enables smooth integration with external Identity Providers like Google, it provides a simple sign-up/sign-in front-end, and it also provides an OAuth REST API (used to integrate with other services when needed authoring data for auditing purposes).

For the `AddressService` it was used a simple JavaScript lambda function to fetch addresses' details (e.g.: latitude and longitude) from OpenStreetMap via REST, and transform the response to a readable JSON for the `RouteWeb` front-end.

For the `RouteService` it was decided to use Spring boot to reuse the source code from an older project and save development time. 
However, the database was migrated to the serverless DynamoDB to save costs as we only pay for what we use. 
The issues regarding using standard Spring Framework in Lambda will be better explored latter on.

For the `OptimizerService` a few tweaks had to be made.
First, it was decided to use Quarkus Native due to its low startup time and memory consumption.
Secondly, it was decided to use SNS and S3 as the messaging system.
The reasoning behind it is that SNS doesn't require a lambda to poll for messages and therefore extra costs are not incurred. Besides that, S3 was combined with SNS because some messages were bigger than 256kb, and then it was necessary to store then in another media and use SNS just to notify about their existence. 
Finally, for the `SolverService`, the lambda function was set up to 1.7GB of memory to enable a full CPU for the solver (the solver is a very CPU-intensive task). Besides that, the lambda timeout was set up to 15 minutes to give the solver more time to find a good itinerary for the route passed for optimization.

## Implementation Details

This section highlights the main architectural decisions take so far for this project.
We focus on a small representation of the decisions taken and the main reasoning behind them.
All details and lines of codes are not scrutinized for the sake of readability of this post.

### Infrastructure-as-code (IaC)

One of the most important decisions was the ability to maintain all infrastructure as code.
An interesting motivation was costs saving because IaC can enable you to delete and re-create everything as you want.
During certain times I was not to focused on developing the software, so I used to drop everything for weeks to save some money.
However, a more technical grounded reasoning for IaC is that it enables you to have clear documentation and versioning control of all infrastructure decisions taken along the way.

CloudFormation was the technology chosen for this project.
This was a vendor lock-in decision because the components used are specific from AWS, no abstraction layers that would enable migration to other cloud platforms were used (e.g.: Kubernetes).
The main benefit is the proximity to AWS documentation, and from my personal opinion, the CloudFormation YAML format is easier to understand than other popular tools like Terraform.

In terms of IaC structure, parts of the infrastructure were grouped per domain in their own stack files to simplify the maintenance.
The table below enumerates all stack files, their components, and their logical execution sequence for creating the infrastructure.
This sequence was defined based on dependencies, the first stack files are executed sooner, so they can export values and provide services used by other stacks further in the execution chain.

| Sequence | Stack File    | Description                                                                                                                                        |
|----------|---------------|----------------------------------------------------------------------------------------------------------------------------------------------------|
| 1        | Github        | Creates OIDC Provider to enable GitHub Actions integration with AWS to run the pipelines                                                           |
| 2        | Params        | Creates base params on SSM, for example:<br/>- Google OAuth clientID and secrets<br/>- HTTPS certificate ARN<br/>- DNS domain names.               |
| 3        | Base          | Creates the following S3 buckets:<br/>- Bucket for Lambda startup with a sample blank application<br/>- Bucket to store the optimizer SNS messages |
| 4        | Front-end     | Creates the front-end S3 bucket, CDN, and Route53 records                                                                                          |
| 5        | Authorization | Creates Cognito user pool, identity provider with Google, OAuth authorization endpoint, user pool client, sign-up/sign-in form                     |
| 6        | Address       | Creates the address service components, like: lambda function, roles, aliases, and the API Gateway routes and authorizers                          |
| 7        | Routes        | Creates the routes service components, like: lambda function, roles, aliases, the API Gateway routes and authorizers, and the DynamoDB table       |
| 8        | API domains   | Creates the api.joaopedroschmitt.click Route53 record to the API gateways from Address and Routes services                                         |
| 9        | Optimizer     | Creates the optimizer lambda functions and roles, SNS topics and permissions                                                                       |

### GitHub Actions

For this project, a multi-repository approach with the following 5 repository was used: `aws-cloud-infrastructure`, `aws-address-service`, `aws-front-end`, `aws-route-lambda`, and `aws-route-optimizer`.
With respect to `aws-route-optimizer`, in this specific case, the repository is a mono-repo managing the microservices `OrchestratorService`, `DistanceService` and `SolverService` all together because they share the same logical domain and Quarkus Native infrastructure.
Thanks to WebIdentity role configuration in IAM, it was possible to integrate GitHub Actions to AWS by creating an OIDCProvider as part of the CloudFormation GitHub stack mentioned in the previous Infrastructure as Code section.

```yaml
AWSTemplateFormatVersion: 2010-09-09
Description: 'Cloudformation for GitHub actions integration'

Resources:
  IDCProvider:
      Type: AWS::IAM::OIDCProvider
      Properties:
          Url: "https://token.actions.githubusercontent.com"
          ClientIdList:
            - "sts.amazonaws.com"
          ThumbprintList:
             - 6938fd4d98bab03faadb97b34396831e3780aea1

  GitHubIAMRole:
      Type: AWS::IAM::Role
      Properties:
          RoleName: GithubActionsDeployRole
          AssumeRolePolicyDocument:
             Statement:
               - Effect: Allow
                 Action: sts:AssumeRoleWithWebIdentity
                 Principal:
                   Federated: !Ref IDCProvider
                 Condition:
                   ForAnyValue:StringLike:
                      token.actions.githubusercontent.com:sub: 
                      - !Sub repo:<GitHub account>/aws-cloud-infrastructure:*
                      - !Sub repo:<GitHub account>/aws-front-end:*
                      - !Sub repo:<GitHub account>/aws-address-service:*
                      - !Sub repo:<GitHub account>/aws-route-optimizer:*
                      - !Sub repo:<GitHub account>/aws-route-lambda:*
          MaxSessionDuration: 3600
          Description: "Github Actions role"
          Policies:
          - PolicyName: 'GithubActionsDeployRole-policy'
            PolicyDocument:
              Version: '2012-10-17'
              Statement:
              - Effect: Allow
                Action: '*'
                Resource: '*'
```

In terms of GitHub Actions workflow configurations, for all repositories a `.github/workflows/deploy.yaml` file was created containing the following base structure. Then, for each repository specific build steps were added accordingly to their tech-stack.


```yaml
{% raw %}
name: <repository name>

on:
  push:
    branches: [main, master]
  workflow_dispatch:

env:
  AWS_ROLE: arn:aws:iam::000000000000:role/GithubActionsDeployRole
  AWS_REGION: <region>
  CI: false

jobs:
  build:
    name: Build
    runs-on: ubuntu-20.04
    permissions:
      id-token: write
      contents: read
    steps:
      - name: Checkout Repository
        uses: actions/checkout@v3
      - name: Configure AWS credentials
        uses: aws-actions/configure-aws-credentials@v1
        with:
          role-to-assume: ${{ env.AWS_ROLE }}
          role-session-name: GitHub-Action-Role
          aws-region: ${{ env.AWS_REGION }}
      - name: build steps specific for each project
{% endraw %}
```

All repositories were configured in a way that any code change pushed would fire the build and deploy process.
Besides that, `aws-cloud-infrastructure` repository was configured to be the leader repository, and was given the capability to fire downstream projects to re-deploy their services every time a specific part of the infrastructure was changed.
For example, the following code snippet from `aws-cloud-infrastructure` shows that everytime the route-stack changes (when `CHANGES_APPLIED == '1'`) then the downstream `aws-route-lambda` repository workflow is dispatched to re-deploy its service.

```yaml
{% raw %}
...
jobs:
  deploy-stack:
    name: Deploy Infrastructure
    runs-on: ubuntu-20.04
    permissions:
      id-token: write
      contents: read
    steps:
      ...
      - name: Deploy Routes stack
        id: routes-stack
        run: $GITHUB_WORKSPACE/cf-routes-stack.sh
      - name: Deploy Routes application
        if: ${{ env.CHANGES_APPLIED == '1' }}
        id: routes-application
        uses: actions/github-script@v6
        with:
          github-token: ${{ secrets.ACTIONS_PERSONAL_ACCESS_TOKEN }}
          script: |
            await github.rest.actions.createWorkflowDispatch({
              owner: 'GitHub account',
              repo: 'aws-route-lambda',
              workflow_id: 'deploy.yaml',
              ref: 'main'
            })
      ...
{% endraw %}
```

From the previous snippet, the environment variable `CHANGES_APPLIED` is set by the script `cf-routes-stack.sh` that identifies a stack modification based on the result of the CloudFormation command. The following snippet shows the details:

```shell
aws cloudformation deploy \
    --region aws-region \
    --template-file cf-routes-stack.yaml \
    --capabilities CAPABILITY_IAM \
    --stack-name routeplanner-routes-stack \
    --fail-on-empty-changeset
if [ $? -eq 0 ]; then
    echo "CHANGES_APPLIED=1" >> $GITHUB_ENV
fi
exit 0
```

### Authorization Service

As mentioned previously Cognito was used as provider for authentication and authorization.
The User Poll was configured to allow signup through a local account or through an external identity provider (in this case Google).
To facilitate the front-end development, a custom oauth domain `oauth.joaopedroschmitt.click` was configured through Route53 and Cognito User Pool CloudFront distribution.
Some issues were found during the creation of the custom domain through CloudFormation, it was not possible to automatically get the CloudFront domain (more details [here](https://github.com/aws-cloudformation/cloudformation-coverage-roadmap/issues/241) and [here](https://gist.github.com/grosscol/3623d2c2affdd3b88ed4538537bb0850)).

The React front-end was developed using the Amplify library, an AWS provided implementation that integrates well with Cognito. 
The following snippet show details about the required dependencies:

```json
{
  ...
  "dependencies": {
    "@aws-amplify/ui-react": "^2.6.1",
    "aws-amplify": "^4.3.14",
    ...
  },
  ...
}

```

To integrate Amplify, React, and Cognito, the following configuration was made as part of the `App.js` file:

```javascript
{% raw %}
import './App.css';
import React, { useEffect, useState } from 'react';
import Amplify, { Auth, Hub } from 'aws-amplify';
...
const isLocalhost = window.location.hostname.includes("localhost");

Amplify.configure({
    Auth: {
        region: `${process.env.REACT_APP_AWS_OAUTH_REGION}`,
        userPoolId: `${process.env.REACT_APP_AWS_OAUTH_USERPOOLID}`,
        userPoolWebClientId: `${process.env.REACT_APP_AWS_OAUTH_USERPOOLWEBCLIENTID}`,
        identityPoolId: `${process.env.REACT_APP_AWS_OAUTH_IDENTITYPOOLID}`,
        oauth: {
            domain: 'oauth.joaopedroschmitt.click',
            scope: [
                'phone',
                'email',
                'profile',
                'openid',
                'aws.cognito.signin.user.admin',
                'api.joaopedroschmitt.click/addresses.read',
                'api.joaopedroschmitt.click/routes.read',
                'api.joaopedroschmitt.click/routes.write'
            ],
            redirectSignIn: isLocalhost ? 'http://localhost:3000' : 'https://joaopedroschmitt.click',
            redirectSignOut: isLocalhost ? 'http://localhost:3000' : 'https://joaopedroschmitt.click',
            responseType: 'code'
        }
    }
});

function App() {
    const [user, setUser] = useState(null);
    useEffect(() => {
        const unsubscribe = Hub.listen("auth", ({ payload: { event, data } }) => {
            switch (event) {
                case "signIn":
                    setUser(data);
                    break;
                case "signOut":
                    setUser(null);
                    break;
                default:
                    break;
            }
        });
        Auth.currentAuthenticatedUser()
            .then(currentUser => setUser(currentUser))
            .catch(() => console.log("Not signed in"));
        return unsubscribe;
    }, []);

    return (
        <Layout style={{ minHeight: "100vh" }}>
            <NavBar
                isLogged={!!user}
                email={user?.attributes?.email}
                signIn={() => Auth.federatedSignIn()}
                signOut={() => Auth.signOut()}>
                ...
            </NavBar>
            ...
        </Layout>
    );
}
export default App;
{% endraw %}
```

Therefore, with this global `Auth` object we were able to get user credentials to make all HTTP requests and send the JWT token as part of the authorization header.
You can see an example at the snippet below:

```javascript
Auth.currentSession().then(res =>
  fetch(`https://api.joaopedroschmitt.click/routes/create`, {
      method: 'POST',
      headers: {
          ...
          "Authorization": res.getAccessToken().getJwtToken()
      },
      body: JSON.stringify(routeDTO)
  })
  ...
```

### Address Service

The `AddressService` was designed to find geographical coordinates for a given search string.
When an user is editing a route (Logical Design section, Step 2) every address searched calls the `AddressService` to obtain a list of possible locations and their coordinates.
The image below presents an example of this use-case for the query "new york".

![Address search](/assets/imgs/search-address-front-end.png)

Behind the scenes `AddressService` calls OpenStreetMap API to obtain location data.
There are many other external providers of such services, like Google Maps,  besides OpenStreetMaps.
However, the advantage of OpenStreetMaps is that it's free.

In essence the `AddressService` works as an anti-corruption layer between the front-end and the external provider.
It basically protects the frond-end from re-work in case we decide to change the external provider as long as the response from the provider can be transformed to the same format.

The general system architecture for the `AddressService` is composed of an AWS API Gateway integrated to Cognito and Lambda. 
The API Gateway communicates to Cognito to authorize the front-end requests using the JWT token sent through the `Authorization` header.
The AWS Lambda function is developed using the JavaScript plataform because the function is quite small and simple and we require a fast cold-start.

### Route Service

The `RouteService` was designed to enable users to configure routes and serve them as a facade for the `OptimizerService`.
Its main responsabilities are: 

1. persist routes for users in durable storage
2. run business validations before saving a route
3. invoke the optimizer service passing only the required information
4. provide well-formatted itinerary details
5. restrict user access so they can only see their owned routes

In terms of persistence it was chosen DynamoDB as the `RouteService` database.
The main motivation was due to its pay for what you use.
This way I don't need to pay for a running RDS instance when the service is idle.

The route table was modeled to enable schema flexibility and quick retrieval of user's routes, the database model is presented in the image below. 
The `ID` attribute is a random UUID value to minimize hot-partitioning issues. 
The attributes `createdAt`, `createdBy`, and `email` are used to associate routes to users and for quick search and recovering (these attributes are indexed). 
Finally, `depot` and `requests` are JSON objects with the details about the route created by the user, this information later on sent to the `OptimizerService`.

![Route DynamoDB schema](/assets/imgs/route-dynamodb-schema.png)

For a while the current schema is not being an issue in terms of storage capacity (400KB max record size), but in the future we may need a different strategy using S3 to persist routes if this limit is not enough anymore.

The same way as the `AddressService`, the `RouteService` runs on top of AWS Lambda.
This was the design decision to save costs and pay only for what we use.
The function is also served through API Gateway that authorizes the requests by validating the JWT token with Cognito.

The tech-stack chosen for this service was mainly Spring-Boot.
This decision was taken due to the experience with the framework and also because it was possible to reuse the code from a previous project.
However, this service is strugling with cold starts, spring takes around 10 seconds to start serving requests for this service.
For the long term it's being considered to migrate the service to either Quarkus Native or GoLang.
The logs below show the cold-start issue, notice the amount of time taken to initialize the function (~8.4 seconds).

![Route Lambda Cold Start problem](/assets/imgs/route-service-spring-cold-start-issue.png)

This service integrates with a couple of AWS services.
It persists data into DynamoDB and sends messages to `OptimizerService` by persisting the route JSON files into S3 and notifying the `OrchestratorService` through SNS.
All these integrations are done using AWS SDK and permissions to the services are granted through a single AWS Role linked to this function.
The image below gives a general glance about the function.

![AWS Route Lambda](/assets/imgs/aws-route-lambda.png)

### Optimizer Service

The `SolverService` is the most complex of all services.
It implements an AI solution to find the best itinerary for a given route (see section Vehicle Routing Problem for more details).
The original implementation of the Solver was developed in Java, and because Java is not a good fit for Lambda functions, it was decided to go for Quarkus Native as it provides smaller startup times, less memory consumption, and it wouldn't require to rewrite the whole solver in another language.
AWS Lambda function doesn't support Quarkus Native executions by default, so a custom Lambda extension was required $^3$.
You can see more details about how to develop and deploy Quarkus Native to AWS using Github Actions [here](https://schmittjoaopedro.github.io/jekyll/update/2022/09/30/quarkus-native-aws-lambda-github-actions.html).

The trade-off with Quarkus Native and other languages is its compilation time, it takes a few minutes to generate the final binary and therefore requires more computation during the build.
However, once the build is done and deployed, Quarkus Native in Lambda functions run quite well. They have a small initalization time and are very efficient in terms of memory consumption.
The image below shows the logs of a single lambda execution of the `SolverService`.
Notice the total time required to instantiate the whole solver took less than 300ms and the whole execution consumed 325MB of memory.
In this image you can also see the log details of the solving process, every time a new best solution (a.k.a. itinerary) is found it's reported in the logs, this search process is very CPU and memory heavy.
Because the `SolverService` is a heavy service, then the Lambda function was set to use 1.7GB of memory RAM so it has a complete CPU to run the code in the most efficient way possible.
For this solver algorithm we don't benefit from parallel execution, so we don't need more CPUs.

![AWS Lambda Quarkus Native](/assets/imgs/aws-quarkus-native-lambda-execution.png)

The other `OrchestratorService` and `DistanceService` run simpler algorithms than the `SolverService`, and therefore require less resources. 
The following image shows the logs for the `DistanceService` after responding a request to calculate the distance matrix for a route.
You can see from this execution that the initalization time was around the same as the previous `SolverService` however it consumed less memory due to its simpler logic, around 80MB.

![Distance AWS Lambda Quarkus Native](/assets/imgs/distance-aws-quarkus-native-lambda-execution.png)

All the three services that compose the `OptimizationService` communicate asynchronously through messages.
It was decided to use SNS as the main tool for this communication because it doesn't require to configure any frequent pooling to fetch the messages (like in SQS), so in theory we should expend less money.
Also the SNS was combined with S3 to send the payload from point A to B, this was necessary because SNS only supports messages up to 256Kb, and most of the payloads overpass this limit.
The way it works is like this, every time a service A wants to send a message to another service B, it first persists the message under a S3 key prefix value equal to the route ID to be optimized. 
Then service A sends as part of the SNS message body the payload file path from the S3 bucket. When service B receives the notification, it fetches the content by opening up the payload from the given path in S3.

## General Performance Analysis

### Address Service Performance

The `AddressService` is a AWS Lambda Function written in Java Script as already explained in the previous sections.
In order to validate it's performance and scalability, a load test was conducted.
The Gatling test framework was used for this task and the details of the simulation can be seen at the snippet below.

```scala
class AddressServiceSimulation extends Simulation {

  val feeder = csv("data/search.csv").random

  val search = {
    feed(feeder)
      .exec(
        http("Search Address")
          .get("/addresses")
          .queryParam("q", "#{searchCriterion}")
          .check(
            status.is(200),
            jsonPath("$[*].id").count.gt(0)
          )
      )
  }

  val httpProtocol = http.baseUrl(Configuration.apiURL)
      .authorizationHeader(Configuration.accessToken)

  var users = scenario("Users").exec(search)

  setUp(
    users.inject(
      constantUsersPerSec(10).during(5.minutes)
      //constantUsersPerSec(50).during(5.minutes)
      //constantUsersPerSec(100).during(5.minutes)
    )
  )
  .protocols(httpProtocol)
  .assertions(
    global.successfulRequests.percent.gt(99)
  )
}
```

This test scenario queries the `AddressService` by passing different query values, and expects the response to be sucessfull.
The system was stressed up until the point it started to fail, it happened when the simulation reached 100 users per second.
You can see at the image below that roughly 3% of all requests failed with 500 status code.

![Address Load Test 1](/assets/imgs/ai-servless-address-load-test-1.png)

Also, in terms of performance perceived by the user, it can be seeing at the graph below that 95% of all requests stayed under 700ms to get a response from the server (including network round-trips between Portugal and Ireland).
Lambda functions take some time to start responding requests, it's known as cold-start issue, it can be seeing at the beginning of the graph where there's a small spike on the response times.

![Address Load Test 2](/assets/imgs/ai-servless-address-load-test-2.png)

The failed requests observed above were probably affected by the Throttling applied during the simulation (the graph below shows around 180 throttles).
Looking at the "Response Time Distribution" at the graph above it can be seeing that it takes less than 200ms to fail a request, that's coherent with the throttling type of error where the request is blocked off before getting to the back-end service.
In terms of resources consumption it can be seeing that around 50 lambda functions were instantiated to hold the load.
It reasons with the observed time of ~600ms, as within each second we have 100 users making requests to the back-end, it takes roughly one lambda function to respond two users.

![Address Load Test 3](/assets/imgs/ai-servless-address-load-test-3.png)

This service is quite performant and scalable given we reached it's limitation by a throttling configuration that could be increased to enable more load.

### Route Service Performance

The `RouteService` project is responsible for owing the flow of recording new routes for optimization.
As mentioned previously it was developed in Spring Boot as per the official documentation.
The goal of this performance test was to validate the complete end-to-end composed by route creating, listing and loading by its ID.
The following code snippets present the script developed in Gatling to run these simulations.

```scala
class RouteServiceSimulation extends Simulation {

  val feeder = csv("data/address.csv").random

  var create = feed(feeder)
      .exec(
        http("Create Route")
          .post("/routes/create")
          .header("Content-Type", "application/json;charset=UTF-8")
          .body(RawFileBody("#{payloadPath}"))
          .check(
            status.is(200),
            jsonPath("$.id").saveAs("routeId")
          )
      )
      .exec(
        http("Find all Routes")
          .get(s"""/routes/createdby/${Configuration.userId}""")
          .check(
            status.is(200),
            jsonPath("$[*].id").count.gt(0)
          )
      )
      .exec(
        http("Find Route by ID")
          .get("/routes/find/#{routeId}")
          .check(
            status.is(200),
            jsonPath("$.id").is("#{routeId}")
          )
      )

  val httpProtocol = http.baseUrl(Configuration.apiURL)
      .authorizationHeader(Configuration.accessToken)

  val users = scenario("Users").exec(create)

  setUp(
    users.inject(
      //constantUsersPerSec(1).during(5.minutes)
      //constantUsersPerSec(2).during(5.minutes)
      constantUsersPerSec(4).during(5.minutes)
    )
  )
  .protocols(httpProtocol)
  .assertions(global.successfulRequests.percent.gt(99))
}
```

Because this service was developed using the standard Spring Boot framwork, we expected it to struggle more to scale due to its slow startup time.
Therefore we started the simulations by setting a lower bar for the load parameters.
The graph below presents the results for the simulation with 4 users per second.

![Route Service Load](/assets/imgs/ai-serverless-route-test-1.png)

In terms of errors, we can see that around 1% of all requests failed, we didn't dig deep enough to prove the root cause, but we suspect from cold-start as currently the service takes ~16sec to start.
It means that receiving a load of 4 users per second during 16 secs yields 64 concurrent lambda functions until the first request is served.
DynamoDB was configured to 50 WCU, it means it supports at maximum 50KB of writting data per second, because each create request saves around 8KB of data, the current configuration supports a peek of ~6 requests per second.
Therefore we hyphotesize that at some point DynamoDB throttled some connections and caused the seeing errors.
However, CloudWatch didn't report any throttling on DynamoDB size but we saw some on the Lambda side.
Maybe collection wasn't granular enough to see the throttling on DynamoDB side.
The following graph shows the metrics collected during the simulation from CloudWatch.

In terms of performance, we can cleary see the impact of cold start by looking at all graphs from the previous image. 
The 95th percentile for the first request was less than 1sec and the 99th percentile was around 17secs.
We can better see the time taken to warm up the Lambda function at the graph "Response Time Percentiles" where the first 20 seconds of the simulation take more time to start responding requests.
This shows that the first requests to hit the lambda function were queued until a response was computed (number of active users almost hit 60).
The side effect is a bunch of lambda functions being created just to deal with the cold-start, and once the load was handled, the number of lambda functions start decreasing to the expected rate of ~5 users per second.
It's interesting to note that Lambda was able to couple with the cold start by dynamically instantiating more functions, but unfortunately all queued users spilled over to DynamoDB when all these users started write data.
As already mentioned, DynamoDB was limited to 6 reqs/sec, therefore we suppose it wasn't able to couple with the load and then throttled some of the requests.

![Route Service Load](/assets/imgs/ai-serverless-route-test-2.png)

### Optimizer Service Performance

The `OptimizerService` is responsible for running the AI Solver that finds the best itinerary possible for a given route.
This service is composed of three micro-services that communicate asynchronously through SNS.
To load test this service, we decided to use Gatling to bulk send messages to `OrchestratorService`.
We didn't request the optimization through `RouteService` to prevent any skewed results from the poor performance of `RouteService` observed from the previous experiment.
Because Gatling doesn't support AWS protocols natively, we only used the framework to bulk load the messages into AWS and not to generate the reports.
The snippet of code below shows how this was achieved.

```scala
class OptimizerServiceSimulation extends Simulation {

  val fileContent = Source
    .fromResource("data/optimizer-request.json")
    .mkString

  val s3Client = S3Client
    .builder()
    .httpClient(UrlConnectionHttpClient.builder().build())
    .build()

  val snsClient = SnsClient
    .builder()
    .httpClient(UrlConnectionHttpClient.builder().build())
    .build()

  val search = {
    exec { session =>
      val routeId = UUID.randomUUID().toString()

      // Upload message to S3
      s3Client.putObject(PutObjectRequest
          .builder()
          .bucket("schmittjoaopedro-click-route-planner-optimizer")
          .key(s"""${routeId}/optimizer-orchestrator-request.json""")
          .build(),
          RequestBody.fromString(fileContent.replaceAll("#route_id#", routeId))
        )

      // Request optimization through SNS
      val attributes = new util.HashMap[String, MessageAttributeValue]()
      attributes.put("routeId", MessageAttributeValue.builder()
        .dataType("String")
        .stringValue(routeId)
        .build())
      attributes.put("messageType", MessageAttributeValue.builder()
        .dataType("String")
        .stringValue("optimizer-orchestrator-request")
        .build())
      snsClient.publish(PublishRequest
          .builder()
          .message(s"""${routeId}/optimizer-orchestrator-request""")
          .topicArn(Configuration.topicArn)
          .messageAttributes(attributes)
          .build()
        )

      session
    }
  }

  val httpProtocol = http.baseUrl(Configuration.apiURL)
      .authorizationHeader(Configuration.accessToken)

  var users = scenario("Users").exec(search)

  setUp(
    users.inject(
      //atOnceUsers(10)
      //atOnceUsers(100)
      atOnceUsers(1000)
    )
  )
  .protocols(httpProtocol)
}
```

From the `OrchestratorService`, the service that communicates with `DistanceService` and `SolverService`, hence requires more throughput to support all communication overload, we can see the main metrics collected from AWS in the image below.
This graph is showing the results obtained from the simulation with 1000 thousand optimization requests.
These graphs aren't granular enough to get precise information about the metrics, but from there we can afirm that more than 2k invocations happened to this service during the period of 5 minutes.
Some throttling was also recorded, but we didn't find any itinerary missing by analysing the final messages sent out from the `OrchestratorService`.
SNS implements a retry policy, it means that when Lambda throttles, SNS service backs off for some time and try again later until it succeeds or reaches the maximum number of retries.
In this case, the service was capable to deal with a load of 1K messages during 5 minutes, that corresponds to 3 requests per second.

![Optimizer Service](/assets/imgs/ai-serverless-optimizer-test-1000.png)

## References

[1] Wikipedia - Vehicle Routing Problem. Available at: https://en.wikipedia.org/wiki/Vehicle_routing_problem

[2] TERMHIGEN-A Hybrid Metaheuristic Technique for Solving Large-Scale Vehicle Routing Problem with Time Windows. Available at: https://www.researchgate.net/figure/Description-of-a-Multi-Node-Vehicle-Routing-Problem-35_fig1_331408676

[3] Quarkus Native on AWS Lambda functions. Available at: https://quarkus.io/guides/amazon-lambda