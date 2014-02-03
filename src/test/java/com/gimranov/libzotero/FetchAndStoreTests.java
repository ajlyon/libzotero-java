/*
 * Copyright (c) Avram Lyon, 2014.
 *
 * This file is part of libzotero-java.
 *
 * libzotero-java is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * libzotero-java is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with libzotero-java.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.gimranov.libzotero;

import com.gimranov.libzotero.model.Item;
import com.gimranov.libzotero.persist.PersistenceAction;
import com.gimranov.libzotero.rx.FlattenIteratorFunc;
import com.gimranov.libzotero.rx.PersistenceLoadFunc;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import retrofit.RequestInterceptor;
import retrofit.RestAdapter;
import retrofit.RetrofitError;
import rx.Observable;
import rx.observables.BlockingObservable;
import rx.util.functions.Func1;

import java.util.List;

import static com.gimranov.libzotero.Credentials.ACCESS_KEY;
import static junit.framework.Assert.*;

public class FetchAndStoreTests {
    private ZoteroService zoteroService;
    private InMemoryPersistence persistence;


    @Before
    public void setUp() throws Exception {
        RequestInterceptor authorizingInterceptor = new RequestInterceptor() {
            @Override
            public void intercept(RequestFacade request) {
                request.addEncodedQueryParam("key", ACCESS_KEY);
                request.addHeader("Zotero-API-Version", "2");
            }
        };

        RestAdapter adapter = new RestAdapter.Builder()
                .setEndpoint("https://api.zotero.org")
                .setRequestInterceptor(authorizingInterceptor)
                .setConverter(new ZoteroConverter())
                .build();

        zoteroService = adapter.create(ZoteroService.class);

        persistence = new InMemoryPersistence();
    }

    @After
    public void tearDown() throws Exception {
        persistence.clear();
    }

    @Test
    public void testGetItemPersist() throws Exception {
        Observable<List<Item>> itemObservable = zoteroService.getItems(LibraryType.USER, Credentials.USER_ID, null, null);

        Observable<List<Item>> cachingObservable = itemObservable.doOnNext(new PersistenceAction(persistence));

        List<Item> results = BlockingObservable.from(cachingObservable).first();

        assertTrue(!results.isEmpty());
        Item persisted = BlockingObservable.from(persistence.load(results.get(0).getItemKey(), Item.class)).first();
        assertNotNull(persisted);
        assertEquals(results.get(0).getItemKey(), persisted.getItemKey());
    }

    @Test
    public void testUse304() throws Exception {
        PersistenceAction persistenceAction = new PersistenceAction(persistence);
        Observable<List<Item>> itemObservable = zoteroService.getItems(LibraryType.USER, Credentials.USER_ID, null, null)
                .doOnNext(persistenceAction);

        // Make the request and persist it all
        List<Item> results = BlockingObservable.from(itemObservable).first();

        Observable<Item> recoveringObservable = zoteroService.getItems(LibraryType.USER, Credentials.USER_ID, null, "1000")
                .flatMap(new FlattenIteratorFunc<Item>())
                .onErrorResumeNext(new PersistenceLoadFunc<>(Item.class, persistence));

        List<Item> cachedResults = recoveringObservable.toList().toBlockingObservable().first();

        assertFalse(cachedResults.isEmpty());
        assertEquals(results.size(), cachedResults.size());

    }
}
