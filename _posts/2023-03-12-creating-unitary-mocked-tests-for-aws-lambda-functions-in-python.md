---
layout: post
title:  "Creating Unitary Mocked Tests for AWS Lambda functions in Python"
date:   2023-03-12 12:00:00 +0100
categories: jekyll update
---

## Motivation

Testing is an essential part of software development. 
Usually, engineers put a lot of effort into testing monolithic and micro-service applications, but what about lambda functions?
Just because lambda functions tend to be small in scope, it doesn't mean they don't need testing.
This blog aims to share some ideas on how you can unit test your lambda functions written in Python by mocking AWS components.
You can get access to the code used across this blog by downloading it from [here](/assets/binaries/lambda-aws-test.zip).

## Folder structure

The sample Lambda function tested in this blog is somewhat opinionated in terms of folder and file structure. Nevertheless, it can be adapted as per your requirements.
The following diagram depicts the general structure:
```
/lambda-function
  |_ src/
  |   |_ modules/
  |   |   |_ __init__.py
  |   |   |_ parser.py
  |   |_ lambda_handler.py
  |_ test/
  |   |_ fixtures/
  |   |   |_ samples.csv
  |   |_ __init__.py
  |   |_ test_lambda_handler.py
  |   |_ test_parser.py
  |_ build.sh
  |_ requirements.txt
```

The folders are organized so to separate the `src` (production) files from the `test` ones. The idea is to facilitate the packaging of the final zip file to upload into AWS Lambda.

Inside the `src` directory, the root file is the `lambda_handler.py`, which will be referenced by the Lambda function. The modules folder is there to help organize your project and make the code cleaner accordingly to SOLID principles.

Inside `test` folders, each `test_*.py` file represents a test for a specific python production file. The `fixtures` folder is mainly used to store whatever resources your lambda function will process in production (in this example a sample CSV file read from S3).

The file `build.sh` contains some commands to install, test, and build your lambda function code. When running in production you'll probably look for different ways of integrating these commands in your pipeline other than a single bash script.

## Dependencies

One of the goals of this post is to show how you can unit test your lambda function with minimal third-party dependencies from other vendors, which means, using as most as possible all standard python libraries.
The code shown in this post only depends on the extra AWS SDK `boto3` library, used to interact with AWS Cloud components.

Besides external dependencies, the folder structure organization allows the local dependencies to be resolved differently from when the code is run inside a Lambda function and from when the tests are run locally.
For example, when running in production all files/folders within the folder `src/*` will be in the root directory. Since the `lambda_handler.py` will be the entry point for this function, all its local imports are relative to its location, ex: `from modules.parser import Parser`.

Now when running the tests locally, we execute the command `python3 -m unittest discover -v` from the `lambda-function` function root directory. It means that for testing the path resolution differs from when running in production. You'll notice that a small tweak is made on the `test_lambda_handler.py` file so that when importing the `lambda_handler` it can also resolve the modules relative to their location. In this case:

```python
import os
import sys
import unittest
import json
from unittest.mock import patch
from unittest.mock import MagicMock

# To resolve handler's imports for modules using its relative path
TEST_DIR = os.path.dirname(os.path.abspath(__file__))
HANDLER_ROOT_DIR = os.path.join(TEST_DIR, '..', 'src')
sys.path.insert(0, HANDLER_ROOT_DIR)

@patch('boto3.client')
class LambdaHandlerTest(unittest.TestCase):
  
  def test_empty_records(self, botoClientMock):
    # When calling the lambda handler
    from src.lambda_handler import handler
    handler(None, None)
```

## Mocking AWS components

Because AWS recommends you create the SDK clients globally so that the function can reuse the context from previous invocations to run faster, it's a bit trickier to get the tests right. 
It's possible to mock the global AWS components using the standard `unittest` library with `patch`. Although there are many ways of doing that, a cleaner way of patching the components is through the `@patch` annotation. The following snippet gives an example:

```python
import os
import unittest
import sys
from unittest.mock import patch
from unittest.mock import MagicMock

@patch('boto3.client')
class LambdaHandlerTest(unittest.TestCase):

    def tearDown(self):
        # Delete the module under test to reload it and get fresh mocked contexts
        del sys.modules['src.lambda_handler']
    
    def simple_s3_mock_test(self, botoClientMock):
        # Mock S3 get_object call
        file = open(f"{TEST_DIR}/fixtures/sample.csv", 'rb')
        botoClientMock("s3").get_object.side_effect = \
            (lambda Bucket, Key: { "Body": file } if Bucket == 'my-simple-bucket' and Key == 'sample.csv' else None)

        # Invokes the lambda function (the mocked components) will be 
        # injected for the handler execution
        from src.lambda_handler import handler
        handler(None, None)
        
        # Assert the mocked object was called
        self.assertEqual(1, len(botoClientMock().get_object.mock_calls))
```

## Integrating (Build and Deploy)

Finally, you can integrate your lambda function tests into your CI/CD. The following bash script gives a glance at some useful commands to build, test, and package your lambda code.

```bash
# Install dependencies
sudo apt install -y zip
python3 -m pip install -r requirements.txt

# Run tests
python3 -m unittest discover -v 

# Clean-up old binaries and cached files
rm -rf bin
find . -name __pycache__ -exec rm -rf {} \;

# Build ZIP all /src content to upload into Lambda
mkdir bin
(cd src && zip -r - .) > bin/source.zip
```