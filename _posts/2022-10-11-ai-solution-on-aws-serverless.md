---
layout: post
title:  "[WIP] Architecting an AI solution on AWS serverless"
date:   2022-10-11 12:00:00 +0100
categories: jekyll update
---

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

### Deploying through GitHub Actions

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
```

All repositories were configured in a way that any code change pushed would fire the build and deploy process.
Besides that, `aws-cloud-infrastructure` repository was configured to be the leader repository, and was given the capability to fire downstream projects to re-deploy their services every time a specific part of the infrastructure was changed.
For example, the following code snippet from `aws-cloud-infrastructure` shows that everytime the route-stack changes (when `CHANGES_APPLIED == '1'`) then the downstream `aws-route-lambda` repository workflow is dispatched to re-deploy its service.

```yaml
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

### Authorisation

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

### Address back-end
- Example of request and response
- Requirement for nodejs
- API Gateway mapping
- Code snippet
### Route back-end
- Example of request and response
- Spring for lambda (Handler, IoC), design choice due to experience
- DynamoDB as database (400kb limit, maybe need a new strategy in the future, e.g.: S3)
- API Gateway (dynamic path mapping and CORS)
- Tests with dynamoDB
- Deploy with GitHub
- Local tests using SAM
### Optimizer back-end
- Example of request and response
- Quarkus for lambda (Handler)
- Native compilation
- Communication through SNS (no event sourcing required)
- S3 for storing messages
- Unit testing (future for test containers)
- Deploy with GitHub
### Front-end
- React development
- Ant Design for forms
- Leaflet for maps
- Front-end authorisation using Amplify (hidden menus)

## General Results

### E2E flow
- Example of user-view E2E flow through simple video
- Example of tech-view E2E flow through diagrams
### Spring Lambda performance
- Average response time (different loads 10, 20, 50 Gatling)
- Huge cold start issue
- Average response time without cold start
- Memory consumption
- Future migration a new stack as the MVP was proved functional, not performant (maybe quarkus or go)
### Quarkus Lambda performance
- Average response time (different loads 10, 20, 50 Gatling)
- Small cold start issue
- Average response time without cold start
- Memory consumption
### General integrated performance 
- Total time to optimize simple route (20 requests - 40 nodes)
- Total time to optimize simple route (100 requests - 200 nodes)
- Total time to optimize simple route (200 requests - 400 nodes)
- Total time to optimize simple route (300 requests - 600 nodes)

## References

[1] Wikipedia - Vehicle Routing Problem. Available at: https://en.wikipedia.org/wiki/Vehicle_routing_problem

[2] TERMHIGEN-A Hybrid Metaheuristic Technique for Solving Large-Scale Vehicle Routing Problem with Time Windows. Available at: https://www.researchgate.net/figure/Description-of-a-Multi-Node-Vehicle-Routing-Problem-35_fig1_331408676