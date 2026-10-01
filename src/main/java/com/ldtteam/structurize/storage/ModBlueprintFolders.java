package com.ldtteam.structurize.storage;

import com.ldtteam.structurize.api.util.Log;
import net.neoforged.neoforgespi.language.IModInfo;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.ldtteam.structurize.api.util.constant.Constants.BLUEPRINT_FOLDER;

/**
 * Finds a mod's built-in structure pack folder ({@code blueprints/<modid>}).
 * <p>
 * Since FML 12 a mod's content roots are folders in a dev run but the jar FILE itself for a packaged mod, so the folder
 * must be resolved inside the jar's zip file system there. Those file systems stay open for the game's lifetime because
 * packs read their blueprints lazily; each jar is opened once and shared by the client and server loaders.
 */
public final class ModBlueprintFolders
{
    private static final Map<Path, FileSystem> JAR_FILE_SYSTEMS = new ConcurrentHashMap<>();

    private ModBlueprintFolders()
    {
    }

    /**
     * @param mod the mod
     * @return its {@code blueprints/<modid>} folder, or null if it ships none.
     */
    @Nullable
    public static Path find(final IModInfo mod)
    {
        return find(mod.getOwningFile().getFile().getContents().getContentRoots(), mod.getModId());
    }

    /**
     * @param contentRoots the mod file's content roots (folders or jar files)
     * @param modId        the mod id
     * @return the first {@code blueprints/<modid>} folder found, or null.
     */
    @Nullable
    public static Path find(final Collection<Path> contentRoots, final String modId)
    {
        for (final Path root : contentRoots)
        {
            final Path base = Files.isRegularFile(root) ? jarRoot(root) : root;
            if (base == null)
            {
                continue;
            }
            final Path folder = base.resolve(BLUEPRINT_FOLDER).resolve(modId);
            if (Files.isDirectory(folder))
            {
                return folder;
            }
        }
        return null;
    }

    @Nullable
    private static Path jarRoot(final Path jar)
    {
        final FileSystem fs = JAR_FILE_SYSTEMS.computeIfAbsent(jar.toAbsolutePath().normalize(), path -> {
            try
            {
                return FileSystems.newFileSystem(path);
            }
            catch (final IOException | RuntimeException e)
            {
                Log.getLogger().warn("Could not open mod file to look for structure packs: " + path, e);
                return null;
            }
        });
        return fs == null ? null : fs.getPath("/");
    }
}
