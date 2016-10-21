Contributing
=====

Remoting library follows the common contribution rules/recommendations in the Jenkins project.
These recommendations are available [here](https://wiki.jenkins-ci.org/display/JENKINS/Beginners+Guide+to+Contributing).

### Proposing pull requests

New features:
* Create pull requests (PRs) against the master branch.

Bugfixes and performance improvements:

* If the bug is reproducible in <code>Remoting 2.x</code>, create pull requests against the [stable-2.x](https://github.com/jenkinsci/remoting/tree/stable-2.x) branch
 * There is no guarantee that complex and barely reviewable fixes get accepted to this stabilization branch
 * Please note that Static Analysis failures do not fail the Pull Request Builder for the [stable-2.x](https://github.com/jenkinsci/remoting/tree/stable-2.x) branch.
You will need to manually checks diffs made by your change.
* Otherwise - create PRs against the master branch

Documentation:
* Master branch stores actual documentation for all versions. 
* Create PRs against it

### Q&A

Use the [Jenkins developer mailing list](https://groups.google.com/forum/#!forum/jenkinsci-dev) or the <code>#jenkins</code> IRC channel on Freenode to sync-up with remoting developers.
In the case of the mailing list, it's a good practice to CC maintainers directly.