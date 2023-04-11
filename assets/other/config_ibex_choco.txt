# Configurator Framework

## Generating Ibex binaries on Linux

In a linux server with docker, execute the following steps:

```bash
> docker run -it -p 17035:5005 maven:3.6.3-openjdk-11-slim /bin/bash
> cd /opt
> apt-get update
> apt-get install -y wget vim python flex bison gcc g++ make pkg-config git
> wget https://github.com/ibex-team/ibex-lib/archive/ibex-2.8.8.tar.gz
> tar xvfz ibex-2.8.8.tar.gz
> mv ibex-lib-ibex-2.8.8/ ibex-2.8.8
> cd ibex-2.8.8
> chmod -R 777 *
> ./waf configure --lp-lib=soplex
> ./waf install
> cd plugins/
> wget https://github.com/ibex-team/ibex-java/archive/1.2.0.tar.gz
> tar xvfz 1.2.0.tar.gz
> mv ibex-java-1.2.0/ ibex-java
> cd ..
> export | grep JAVA_HOME
> ./waf configure --enable-shared --with-jni --java-package-name=org.chocosolver.solver.constraints.real
> ./waf install
> ./waf configure --enable-shared --with-jni --java-package-name=org.chocosolver.solver.constraints.real
> ./waf install
> ./waf install
> cd examples
> mkdir java
> cd java
> touch App.java
> vim App.java
```

Then in App.java, past the following program:

```java
import org.chocosolver.solver.constraints.real.Ibex;

public class App {

    public static void main(String[] args) {
        Ibex ibex = new Ibex(new double[]{0.000001, 0.000001}, true);
        ibex.add_ctr("{0}=acos({1})");

        System.out.println(Math.pow(10, 6));
        ibex.build();
        System.out.println(Math.pow(10, 6));

        double domains[] = {-10_000.0, 10_000.0, -0.6, 0.6};
        System.out.println("([" + domains[0] + "," + domains[1] + "] ; [" + domains[2] + "," + domains[3] + "])");
        int result = ibex.contract(0, domains, 0.001);
        if (result == Ibex.FAIL) {
            System.out.println("Failed!");
        } else if (result == Ibex.CONTRACT) {
            System.out.println("After contract:");
            System.out.println("([" + domains[0] + "," + domains[1] + "] ; [" + domains[2] + "," + domains[3] + "])");
        } else {
            System.out.println("Nothing.");
        }
        ibex.release();
    }
}
```

Then compile and execute with the following commands:

```bash
> javac -cp ".:/usr/local/share/java/org.chocosolver.solver.constraints.real.jar" App.java
> export LD_LIBRARY_PATH=/usr/local/lib/
> java  -cp ".:/usr/local/share/java/org.chocosolver.solver.constraints.real.jar" App
```

Besides that update the binaries files in `\plataform\linux\ibex` folder of this repository with new binaries from `/usr/bin/lib/*` (ignore python file).

## Generating binaries on Windows

Installing Ibex for Windows and running the foo example

Tutorial based by this http://www.ibex-lib.org/doc/install.html#windows

Extra links:
- Choco Ibex Docs: https://choco-solver.readthedocs.io/en/latest/4_advanced.html#ibex-solver
- Ibex Instalation: http://www.ibex-lib.org/doc/install.html#installation-as-a-dynamic-library
- Ibex Choco Plugin: http://www.ibex-lib.org/doc/java-install.html?highlight=choco
- Ibex 2.8.8 Download: https://github.com/ibex-team/ibex-lib/releases/tag/ibex-2.8.8
- Ibex Java 1.2.0: https://github.com/ibex-team/ibex-java/releases/tag/1.2.0

The directories are the following:
* MingGW = C:\MinGW
* Ibex = C:\MinGW\msys\1.0\home\jschmitt\Ibex\ibex-2.8.8
* JDK = C:\java\jdk_1.8_231_x86

First, extract: ibex-2.8.8.zip (https://github.com/ibex-team/ibex-lib/archive/ibex-2.8.8.zip) in `C:\MinGW\msys\1.0\home\jschmitt\Ibex`. Then rename the folder to ibex-2.8.8;

Open bash terminal: C:\MinGW\msys\1.0\msys.bat

Execute the following commands:
```bash
> cd Ibex/ibex-2.8.8/
> ./waf configure --prefix=/c/MinGW/msys/1.0/home/jschmitt/Ibex/ibex-2.8.8
> ./waf install
> export PKG_CONFIG_PATH=/c/MinGW/msys/1.0/home/jschmitt/Ibex/ibex-2.8.8/share/pkgconfig/
> cd examples
```

Create the following foo.cpp program
```c
#include "ibex.h"
#include <iostream>

using namespace std;
using namespace ibex;

int main(int argc, char** argv) {
  Interval x(0,1);
  cout << "My first interval:" << x << endl;
}
```

Execute in bash:
```bash
> make foo
> ./foo
My first interval:[0, 1]
```

Configuring IBEX for Choco (java 32bits).
* Download de ibex-java lib ([link](https://github.com/ibex-team/ibex-java/archive/1.2.0.zip)
* Unzip in the folder: /c/MinGW/msys/1.0/home/jschmitt/Ibex/ibex-2.8.8/plugins/
* Rename the folder `ibex-java-1.2.0` to `ibex-java`
```bash
> export JAVA_HOME=/c/java/jdk1.8.0_171
> ./waf configure --lp-lib=soplex --prefix=/c/MinGW/msys/1.0/home/jschmitt/Ibex/ibex-2.8.8 --enable-shared --with-jni --java-package-name=org.chocosolver.solver.constraints.real
> export PATH=$PATH:/c/MinGW/msys/1.0/home/jschmitt/Ibex/ibex-2.8.8/lib
> ./waf install (maybe it will be necessary to run more times the install)
```

Now, ibex is installed. It means that you should see the following files in the directory: C:\MinGW\msys\1.0\home\jschmitt\Ibex\ibex-2.8.8\lib:

* ibex/
  * 3rd
    * libprim.a
    * libprim.la
    * libsoplex.a
    * libsoplex.mingw.x86.gnu.opt.a
    * libsoplex-3.1.1.mingw.x86.gnu.opt.a
  * ibex.dll
  * ibex.dll.a
  * ibex-java.dll
  * ibex-java.dll.a
  * libibex.a

Set the windows env. variable on PATH: `C:\MinGW\msys\1.0\home\jschmitt\Ibex\ibex-2.8.8\lib`.
Besides that update the binaries files in `\plataform\windows\ibex` folder of this repository with new binaries from `ibex-2.8.8\lib`.

### Copying IBEX binaries for choco on windows

To use the configurator framework, you will have to download the dependent libraries specific for your platform.

* [For windows](platform/windows/ibex)
* [For linux](platform/linux/ibex)

Copy the IBEX folder and all its files in a directory of your operational system. Ex:
* For windows: C:\projects\configurator_libs\ibex
* For linux: /opt/configurator_libs/ibex

Then create an environment variable in your operational system pointing to this directory:
* Windows: IBEX_HOME: C:\projects\configurator_libs\ibex
* Linux: IBEX_HOME: /opt/configurator_libs/ibex

Finally, add this environment variable to your PATH variable.
* Windows: add %IBEX_HOME% in the PATH variable