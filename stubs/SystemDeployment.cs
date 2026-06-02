using System;
using System.Reflection;

[assembly: AssemblyVersion("4.0.0.0")]
[assembly: AssemblyTitle("System.Deployment")]
[assembly: AssemblyProduct("Mono Stub for Raritan AKC")]

namespace System.Deployment.Application
{
    public sealed class ApplicationDeployment
    {
        private ApplicationDeployment() { }

        public static bool IsNetworkDeployed
        {
            get { return false; }
        }

        public static ApplicationDeployment CurrentDeployment
        {
            get { throw new InvalidDeploymentException("Not network-deployed (stub)"); }
        }

        public Uri ActivationUri { get { return null; } }
        public Uri UpdateLocation { get { return null; } }
        public Version CurrentVersion { get { return new Version(1, 0, 0, 0); } }
        public string DataDirectory { get { return AppDomain.CurrentDomain.BaseDirectory; } }
        public bool IsFirstRun { get { return false; } }
    }

    public class InvalidDeploymentException : Exception
    {
        public InvalidDeploymentException() : base() { }
        public InvalidDeploymentException(string msg) : base(msg) { }
        public InvalidDeploymentException(string msg, Exception inner) : base(msg, inner) { }
    }

    public class DeploymentException : Exception
    {
        public DeploymentException() : base() { }
        public DeploymentException(string msg) : base(msg) { }
        public DeploymentException(string msg, Exception inner) : base(msg, inner) { }
    }
}
