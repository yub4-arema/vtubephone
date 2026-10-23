using System;
using System.IO;
using UnityEditor;
using UnityEditor.Build;
using UnityEditor.Build.Reporting;
using UnityEngine;

namespace VtubePhone.Editor
{
    public static class BuildScript
    {
        public static void Configure()
        {
            PlayerSettings.companyName = "vtubephone";
            PlayerSettings.productName = "vtubephone";
            PlayerSettings.SetApplicationIdentifier(NamedBuildTarget.Android, "com.vtubephone.app");
            PlayerSettings.SetScriptingBackend(NamedBuildTarget.Android, ScriptingImplementation.IL2CPP);
            PlayerSettings.Android.targetArchitectures = AndroidArchitecture.ARM64;
            PlayerSettings.Android.minSdkVersion = AndroidSdkVersions.AndroidApiLevel26;
            PlayerSettings.Android.targetSdkVersion = AndroidSdkVersions.AndroidApiLevelAuto;
            PlayerSettings.defaultInterfaceOrientation = UIOrientation.Portrait;
            PlayerSettings.Android.applicationEntry = AndroidApplicationEntry.Activity;
            var settings = new SerializedObject(AssetDatabase.LoadAllAssetsAtPath("ProjectSettings/ProjectSettings.asset")[0]);
            settings.FindProperty("activeInputHandler").intValue = 1;
            settings.ApplyModifiedPropertiesWithoutUndo();
            AssetDatabase.SaveAssets();
        }
        public static void BuildAndroid()
        {
            Configure();
            if (!BuildPipeline.IsBuildTargetSupported(BuildTargetGroup.Android, BuildTarget.Android))
                throw new BuildFailedException("Android Build Support is not installed");
            if (!EditorUserBuildSettings.SwitchActiveBuildTarget(BuildTargetGroup.Android, BuildTarget.Android))
                throw new BuildFailedException("Could not switch to Android");
            SceneBuilder.BuildAll();
            Directory.CreateDirectory("Builds");
            bool development = Environment.GetEnvironmentVariable("VTUBEPHONE_DEVELOPMENT_BUILD") == "1";
            var report = BuildPipeline.BuildPlayer(new BuildPlayerOptions {
                scenes = new[] { SceneBuilder.ScenePath }, locationPathName = "Builds/vtubephone.apk",
                target = BuildTarget.Android, options = development ? BuildOptions.Development : BuildOptions.None
            });
            if (report.summary.result != BuildResult.Succeeded) throw new BuildFailedException(report.summary.result.ToString());
        }
    }
}
