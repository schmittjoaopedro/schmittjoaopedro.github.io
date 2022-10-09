---
layout: post
title:  "Continuous Integration with jenkins-pipeline for Java, Jira, Slack, Maven, Wildfly and Soapui."
date:   2019-07-04 12:00:00 +0100
categories: jekyll update
---

One of the most difficulties that I have found during the Jenkins-pipeline development for a Java application with Maven and Wildfly was: how to integrated this old-fashioned java application with many different tools used by the development team? Today there are many posts demonstrating single pieces of the puzzle, however, there is no guide that unifies all these pieces in a single overall example. Therefore, my idea here is to grab all these different pieces of information that I had collected to solve a specific problem and create an applied scenario using declarative Jenkins-pipeline.

The idea here is to create a continuous integration process. This process uses a beta server to execute integration and functional tests to verify if the application is working correctly. To this, the following steps must be executed:

* Show the current version of the project managed by Jira;
* Build the application using maven, where the application tests are executed locally (Unitary and Integration tests);
* Un-deploy the current war version from the beta server;
* Copy jar libs for a network folder. This app requires these files to execute some internals;
* Restart the best server;
* Deploy new war version on beta;
* Execute functional tests (Integration, Services, and Load). These are Soapui tests that have their own pipeline on Jenkins;
* Create a tag on source code to manage the latest stable version;
* Publish the latest stable war on nexus;
* Send a notification on Slack about the build status;

The general idea is that the pipeline should execute the build and verify if everything works well before to publish the app on production. In this process, firstly we make the build locally (using maven) and then we publish the war on the beta server. After that, sub-processes on Jenkins are started (using the build directive) and all these are waited to execute completely, even if one of these brokes (directive catchError). It allows to execute all tests and verify the ones that broke, in the other case, the build would be stoped in the first failure and after fixing the broken test we must execute the build again to check if the next tests are ok. Finally, after all, tests had passed, we update the latest tag on git to point for the hash of the latest stable version, and we update the artifact on nexus. At this time, a continuous deployment could verify this build status and make a deploy on production. To communicate the build status, a slack message is sent for the app team.

The following Jenkins-pipeline file describes in details the process:

```groovy
#!groovy​

// By default this pipeline makes checkout from the app git repository on the container.
pipeline {

    // Uses a docker agent with java, maven and git installed
    agent { label 'app-pipeline-deploy' }

    environment {
        // Defines the connection configuration name for the jira-steps-plugin
        JIRA_SITE = 'JIRA-STEP'
    }

    stages {
        stage('Version information from Jira') {
            steps {
                // Changes to the development directory from the project repository
                dir('development') {
                    // Opens a groovy script environment
                    script {						
                        // Read the current jira version from a file managed by the develpoment team.
                        def currentJiraVersion = readFile "jira.version"						
                        // Obtain information from jira related with the currentJiraVersion
                        def version = jiraGetVersion id: currentJiraVersion                        
                        // Log on jenkins console information about the current version
                        def logText =
                            "=================== TESTS ON BETA FOR NEXT JIRA VERSION ===================\n" +
                            "ID: " + version.data.id + "\n" +
                            "NAME: " + version.data.name + "\n" +
                            "DESCRIPTION: " + version.data.description + "\n" +
                            "RELEASED: " + version.data.released + "\n" +
                            "==========================================================================="
                        echo logText
                    }
                }
            }
        }

        stage('Tests and Build') {
            steps {
                dir('development') {
                    // Use groovy to execute shell commands
                    script {
                        // Give full permision for the build on the development directory
                        sh 'chmod -R 777 *'
                        // Executes maven tests (unitary and integration) and build the app .war file
                        sh 'mvn -B install'
                    }
                }
            }
        }

        stage('Un-deploy from beta') {
            steps {
                dir('development') {
                    script {
                        // Uses jboss maven plugin to undeploy the current app version from the beta server
                        sh 'mvn -B -DjbossHost=app-beta.app.com -DjbossUser=xxxxxx -DjbossPass=xxxxxx wildfly:undeploy -Papp-beta'
                    }
                }
            }
        }

        stage('Copy app libs') {
            steps {
                dir('development') {
                    // Uses groovy to extract jar libs from the app and copy these for a network directory
                    script {
                        sh 'mkdir app-lib-temp'
                        sh 'cp app/target/app.war app-lib-temp'
                        sh 'cd app-lib-temp && jar -xf app.war'
                        sh 'cd ..'
                        sh 'rm -rf /applib/*'
                        sh 'cp app-lib-temp/WEB-INF/lib/* /applib'
                    }
                }
            }
        }

        stage('Restart beta') {
            // Uses the jenkins master to restart the beta. It is used because this one has the private-key-file necessary for ssh connection
            agent { label 'master' }
            options { skipDefaultCheckout(true) } // Ignore git checkout
            steps {
                script {
                    // Execute ssh command and ignore any response message that could block the command line
                    sh 'sudo ssh app-beta.app.com \'service jboss-as-beta restart &> /dev/null < /dev/null &\''
                    // Wait server to restart
                    sh 'sleep 120'
                }
            }
        }

        stage('Deploy on beta') {
            steps {
                dir('development') {
                    // Back to the docker container, execute deploy of the generated jar to the beta server using maven wildfly plugin
                    script {
                        sh 'mvn -B -DjbossHost=app-beta.app.com -DjbossUser=xxxxx -DjbossPass=xxxxx wildfly:deploy-only -Papp-beta'
                    }
                }
            }
        }

        // In this stage, sub-builds are started by this build on jenkins. 
        // It means that the current build will wait until those builds finishes to proceed.
        // Builds are executed in parallel to speed-up the execution time.
        // Finally, those builds are executed pointing to the fresh version deployed on beta.
        stage('Functional tests') {
            parallel {
                stage('Integration tests') {
                    steps {
                        catchError {
                            build 'soapui-test-pipeline-name1'
                        }
                        catchError {
                            build 'soapui-test-pipeline-name2'
                        }
                    }
                }
                stage('Services tests') {
                    steps {
                        catchError {
                            build 'soapui-test-pipeline-name3'
                        }
                        catchError {
                            build 'soapui-test-pipeline-name4'
                        }
                    }
                }
                stage('Load tests') {
                    steps {
                        catchError {
                            build 'soapui-test-pipeline-name5'
                        }
                        catchError {
                            build 'soapui-test-pipeline-name6'
                        }
                    }
                }
            }
        }

        stage('Create latest tag') {
            // If the build not failed in any stage (including the sub-builds triggered in the Functional tests) the latest tag will be created.
            when {
                expression { currentBuild.currentResult == 'SUCCESS' }
            }
            steps {
                // Uses credentials configuration from jenkins to execute commands on git
                withCredentials([usernamePassword(credentialsId: 'xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx', usernameVariable: 'USER_NAME', passwordVariable: 'USER_PASS')]) {
                    sh '''
                        git push http://$USER_NAME:$USER_PASS@sourcecode.app.com:8080/app/git/development/app:refs/tags/latest
                        git tag -f latest
                        git push http://$USER_NAME:$USER_PASS@sourcecode.app.com:8080/app/git/development/app latest
                    '''
                }
            }
        }

        stage('Publish on Nexus as latest') {
            // If the build not failed in any stage (including the sub-builds triggered in the Functional tests) the latest war will be deployed on nexus.
            when {
                expression { currentBuild.currentResult == 'SUCCESS' }
            }
            steps {
                dir('development') {
                    script {
                        sh 'mvn -B deploy -DskipTests -Dpmd.skip=true'
                        sh 'mvn -B deploy -DskipTests -Dpmd.skip=true -Dapp.version=latest'
                    }
                }
            }
        }
    }

    // Finally, notify the slack group about the build status
    post {
        success {
            slackSend baseUrl: 'https://app.slack.com/services/hooks/jenkins-ci/',
                channel: '#app',
                color: 'good',
                token: 'xxxxxxxxx',
                message: "A new version of the APP is avaiable to production (pipeline-app). The latest tag was updated on Git and the latest version was published on Nexus"
        }
        failure {
            slackSend baseUrl: 'https://app.slack.com/services/hooks/jenkins-ci/',
                channel: '#app',
                color: 'bad',
                token: 'xxxxxxxxx',
                message: "The app-beta ${currentBuild.fullDisplayName} is broken."
        }
    }

}
```