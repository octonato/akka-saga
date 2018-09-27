# akka-saga

## Implementation notes

This is an exploration of how to use akka cluster to perform in a transactional manner. The behavior
should mimic a Saga or what used to be called an Akka Process Manager Pattern. The difference is that
this type of saga is co-located in the same cluster as the business functionality it interacts with,
making it more realtime in nature.

The saga itself is a long-running Akka persistent actor, sharded across the cluster. The saga will 
remain active until either all transactions are commited OR all transactions are rolled back due to 
any error or business exception.

Interestingly, any business persistent actor participating in a saga will essentially be "locked"
during the saga, meaning that the actor may not participate in any other sagas until the initial 
one has completed. I use Akka Stash for this.

Patterns used here are event sourcing of all business components, including the sage as well as 
CQRS (just commands for now).

There is full integration into the Lightbend Enterprise Suite 2.0 for visibility of behaviors.

## The use case

This is a use case I heard not once but twice in the financial sector. It involves a batch of bank
account transactions, in this case withdrawals and deposits. If any single one of the transactions
fail, the entire batch must fail.

## Deployment

This is completely ready to deploy using our enterprise suite tooling onto Kubernetes Minikube.

Steps ---- TODO