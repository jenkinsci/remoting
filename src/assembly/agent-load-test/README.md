Jenkins Remoting Agent Load Tester
==================================

This is a test utility to determine the capacity limits of your Jenkins controller.
Using this utility, you can fire up a remoting server that accepts a number of
loopback connections in order to see what load the JNLP remoting protocols
produce on your server.

To get a comparable set of measures you can run the following commands:

* Unix systems

        bin/agent-load-test --protocol JNLP2-connect --warmup 60 --collect 60 --stats stats.csv
        bin/agent-load-test --protocol JNLP2-connect --warmup 60 --collect 60 --stats stats.csv --bio
        bin/agent-load-test --protocol JNLP3-connect --warmup 60 --collect 60 --stats stats.csv --bio
        bin/agent-load-test --protocol JNLP4-connect --warmup 60 --collect 60 --stats stats.csv
        bin/agent-load-test --protocol JNLP4-connect --warmup 60 --collect 60 --stats stats.csv --bio
        bin/agent-load-test --protocol JNLP4-plaintext --warmup 60 --collect 60 --stats stats.csv
        bin/agent-load-test --protocol JNLP4-plaintext --warmup 60 --collect 60 --stats stats.csv --bio

* Windows systems

        bin\agent-load-test --protocol JNLP2-connect --warmup 60 --collect 60 --stats stats.csv
        bin\agent-load-test --protocol JNLP2-connect --warmup 60 --collect 60 --stats stats.csv --bio
        bin\agent-load-test --protocol JNLP3-connect --warmup 60 --collect 60 --stats stats.csv --bio
        bin\agent-load-test --protocol JNLP4-connect --warmup 60 --collect 60 --stats stats.csv
        bin\agent-load-test --protocol JNLP4-connect --warmup 60 --collect 60 --stats stats.csv --bio
        bin\agent-load-test --protocol JNLP4-plaintext --warmup 60 --collect 60 --stats stats.csv
        bin\agent-load-test --protocol JNLP4-plaintext --warmup 60 --collect 60 --stats stats.csv --bio
