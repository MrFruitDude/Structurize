package com.ldtteam.structurize.client;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * PF1: the per-preview renderer cache of {@link BlueprintHandler}. One renderer, and so one cached mesh, per
 * {@link RenderingCacheKey}: rotating, mirroring or switching blueprint selects (or builds) a different renderer,
 * while moving the ghost keeps the same one. Entries expire after {@link BlueprintHandler#CACHE_EXPIRE_SECONDS}
 * unused and are closed on removal.
 */
final class PreviewRendererCache
{
    private PreviewRendererCache()
    {
    }

    static <R extends AutoCloseable> LoadingCache<RenderingCacheKey, R> create(final Function<RenderingCacheKey, R> factory)
    {
        return CacheBuilder.newBuilder()
            .expireAfterAccess(BlueprintHandler.CACHE_EXPIRE_SECONDS, TimeUnit.SECONDS)
            .<RenderingCacheKey, R>removalListener(entry -> {
                try
                {
                    entry.getValue().close();
                }
                catch (final Exception exception)
                {
                    throw new IllegalStateException("Closing a blueprint preview renderer failed", exception);
                }
            })
            .build(new CacheLoader<>()
            {
                @Override
                public R load(final RenderingCacheKey key)
                {
                    return factory.apply(key);
                }
            });
    }
}
