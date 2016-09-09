# [Aardvark](https://aardvark-data.github.io/aardvark/)
Aardvark is a documentation and automation tool for normalizing data sets. It utilizes declarative documentation about problem domains, data sets, and about the capabilities of data transformation/compute engines. Metadata goes in, and generated transformation code comes out. Aardvark is a "design-time" solution, and is agnostic about "run-time" compute solutions, with a framework that will support ETL solutions, Big Data technologies such as Pig, Spark, and Hive, and SQL and NoSQL engines. 

## Getting Started
Aardvark requires Java 1.7+ and [Leiningen](http://leiningen.org/) to build and to launch in local development mode. 

  * launch aardvark with: _lein ring server_. If a browser does not open automatically, navigate to http://localhost:3000
  * build a deployable .war with _lein ring uberwar_

Log in with admin/admin 
