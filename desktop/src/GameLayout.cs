using System;
using System.IO;

// Resolve resources by layout, without moving or rewriting the installed game.
sealed class InstalledGame {
    public string Root,Assets,ModAssets,PatchAssets,NativeBin,Variant,SelectedPath;
    static bool FileAt(string root,string relative){return File.Exists(Path.Combine(root,relative));}
    static InstalledGame At(string root,string variant){
        if(!Directory.Exists(Path.Combine(root,"assets"))||!Directory.Exists(Path.Combine(root,"modassets")))return null;
        string binary=variant==""?root:Path.Combine(root,variant),patch=Path.Combine(binary,"patchassets");
        bool config=variant==""?FileAt(root,"modassets/assets/bundle/configs.xeon"):FileAt(patch,"assets/bundle/configs.xeon");
        if(!FileAt(binary,"classes.jar")||!config)return null;
        string native=Path.Combine(binary,"win64/java/bin");if(!Directory.Exists(native))native=Path.Combine(binary,"win64");
        return new InstalledGame{SelectedPath=binary,Root=root,Assets=Path.Combine(root,"assets"),ModAssets=Path.Combine(root,"modassets"),PatchAssets=Directory.Exists(patch)?patch:null,NativeBin=Directory.Exists(native)?native:null,Variant=variant==""?"launcher":variant};
    }
    public static InstalledGame Resolve(string selected){
        if(String.IsNullOrWhiteSpace(selected))throw new IOException("Выберите папку установленной игры.");
        string path=Path.GetFullPath(selected.Trim());if(File.Exists(path))path=Path.GetDirectoryName(path);
        if(path.Length>Path.GetPathRoot(path).Length)path=path.TrimEnd(Path.DirectorySeparatorChar,Path.AltDirectorySeparatorChar);
        string leaf=Path.GetFileName(path).ToLowerInvariant();
        if(leaf=="bin_ru"||leaf=="bin_global"){
            string parent=Path.GetDirectoryName(path);var explicitVariant=At(parent,leaf);if(explicitVariant!=null)return explicitVariant;
        }
        foreach(string child in new[]{"","game","client","contents"}){
            string root=Path.Combine(path,child);foreach(string variant in new[]{"","bin_ru","bin_global"}){var result=At(root,variant);if(result!=null)return result;}
        }
        throw new IOException("Не найдены ресурсы игры. Выберите корень установленной игры из Steam (с assets, modassets и bin_ru/bin_global) или лаунчера. На Linux через Wine путь обычно начинается с Z:\\home\\. Проверьте целостность файлов в Steam.");
    }
    public static bool TryResolve(string selected,out InstalledGame result){try{result=Resolve(selected);return true;}catch(IOException){result=null;return false;}catch(ArgumentException){result=null;return false;}}
}

