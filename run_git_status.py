import os
import subprocess

def run_git_status():
    env = os.environ.copy()
    env["PATH"] = "/usr/bin:/bin"  # bypass local wrapper
    result = subprocess.run(["/usr/bin/git", "status", "--porcelain"], capture_output=True, text=True, env=env)
    print(result.stdout)

run_git_status()
