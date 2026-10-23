using System.IO;
using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;
using VtubePhone.Runtime;

namespace VtubePhone.Editor
{
    public static class SceneBuilder
    {
        public const string ScenePath = "Assets/VtubePhone/Scenes/HapticsBench.unity";
        public static void BuildAll()
        {
            BuildScript.Configure();
            var scene = EditorSceneManager.NewScene(NewSceneSetup.EmptyScene, NewSceneMode.Single);
            new GameObject("HapticsBench").AddComponent<HapticsBench>();
            Directory.CreateDirectory("Assets/VtubePhone/Scenes");
            EditorSceneManager.SaveScene(scene, ScenePath);
            EditorBuildSettings.scenes = new[] { new EditorBuildSettingsScene(ScenePath, true) };
            AssetDatabase.SaveAssets();
        }
    }
}
