#!/bin/bash

# Variables
REPO_URL="https://github.com/jenkinsci/remoting.git"
PROJECT_DIR="remoting"
MAVEN_CMD="mvn clean install"

# Update package list
sudo apt update

# Install Git and Maven
sudo apt install -y git maven

# Clone the repository
if [ ! -d "$PROJECT_DIR" ]; then
  git clone $REPO_URL
else
  echo "Project directory already exists. Pulling latest changes..."
  cd $PROJECT_DIR
  git pull origin master
  cd ..
fi

# Navigate to project directory
cd $PROJECT_DIR

# Build the project
$MAVEN_CMD

# Return to the original directory
cd ..

echo "Build completed!"
