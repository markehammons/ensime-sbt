#!/bin/sh

# Integration test asserting that the .ensime file has not changed for a public
# github project.
#
# USAGE: ./test-github.sh org repo commit
#
# e.g. ./test-github.sh ensime ensime-server 8ae9c30631030a9e828426fc021a7eb80c71cd42
#
# The baseline (for Drone runs) should be at
#
#   drone-$ORG-$REPO-$COMMIT.expected
#   drone-meta-$ORG-$REPO-$COMMIT.expected
#
# The ensime-sbt plugin must be installed prior to running the test,
# see the `prepare-testing' task.

cd "`dirname $0`"

PLUGIN=$PWD/../ensime-sbt-install

ORG=$1
REPO=$2
COMMIT=$3

BASELINE=$PWD/drone-$REPO-$COMMIT.expected
BASELINE_META=$PWD/drone-meta-$REPO-$COMMIT.expected

# get the repo
curl -LO https://github.com/$ORG/$REPO/archive/$COMMIT.zip
unzip -qo $COMMIT.zip
cd $REPO-$COMMIT

# install the plugin
if [ ! -d project ] ; then
    echo "$PWD does not appear to be an sbt project"
    exit 1
fi

touch project/plugins.sbt
# removes old versions
grep -v ensime-sbt project/plugins.sbt > tmp
cat tmp $PLUGIN > project/plugins.sbt

sbt gen-ensime gen-ensime-meta

if [ ! -f $BASELINE ] ; then
    echo "Your baseline is:"
    cat .ensime
    exit 1
fi

if [ ! -f $BASELINE_META ] ; then
    echo "Your meta baseline is:"
    cat project/.ensime
    exit 1
fi

# diff returns non-zero if files are different
diff $BASELINE .ensime && diff $BASELINE_META project/.ensime
