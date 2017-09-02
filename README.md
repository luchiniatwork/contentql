# ContentQL

ContentQL allows one to access Contentful data using Om Next Queries.

[Contentful](https://www.contentful.com/) is a popular headless, cloud-based CMS system. Beyond
being purely an API-first CMS system it also supports somewhat complex data schemas, responsive
images and webhooks.

Despite the great features provided by Contentful, one thing remains somewhat challenging:
its API is not necessarily the greatest.

Instead of porting Contentful's API on a one-on-one basis to Clojure and ClojureScript, this
library takes an abstraction route to querying: it uses Om Next's Query language as its main
interface.

## Table of Contents

* [Getting Started](#getting-started)
* [Motivation](#motivation)
* [Query Syntax](#query-syntax)
* [Usage](#usage)
* [Pagination](#pagination)
* [Bugs](#bugs)
* [Help!](#help)

## Getting Started

Add the following dependency to your `project.clj` file:

[![Clojars Project](http://clojars.org/luchiniatwork/contentql/latest-version.svg)](http://clojars.org/luchiniatwork/contentql)

## Motivation

By using Om Next queries one can:

* easily describe deep nested joins
* clearly parameterize root (as well as other) queries
* easily express field selections
* easily dispatch queries to Contentful from Om Next remotes
* easy-to-use responsive images as part of the query
* describe your queries using macro syntax

## Query Syntax

The Om Next query syntax is beautifuly described by António Monteiro [here](https://anmonteiro.com/2016/01/om-next-query-syntax/) but if you need a quick primer, here it is:

### Simple properties

If you have a content type called `blogs`, you can query its entries with:

```clojure
[:blogs]
```

You can always combine several content types in one go:

```clojure
[:blogs :articles]
```

### Joins

If you want just the `title` and the `body` of your `blogs`, you can use a join such as:

```clojure
[{:blogs [:title :body]}]
```

### Nested joins

Assuming your `blogs` content type has an `author` embedded whose `name` you want to fetch
as well, simply nest your joins:

```clojure
[{:blogs [:title :body
          {:author [:name]}]}]
```

This will continue to give you the `title` and the `body` of each blog entry but now also
the name of each blog's author.

### Parametrized queries

Queries can be parametrized by using a list where the second element is a map of parameters.
If you want the blog identified by `id` `"3x1YMtJ1CoOWk0ycYsOw4I"` you can fetch it with:

```clojure
[(:blogs {:id "3x1YMtJ1CoOWk0ycYsOw4I"})]
```

This can be combined with joins, ie:

```clojure
[({:blogs [:title :body]} {:id "3x1YMtJ1CoOWk0ycYsOw4I"})]
```

Or even nested joins:

```clojure
[({:blogs [:title :body
           {:author [:name]}]}
  {:id "3x1YMtJ1CoOWk0ycYsOw4I"})]
```
### Supported parameters for Query roots

All your content types can be queries as part of a query root.

You've already seen above how to query a specific entry by its `id`:

```clojure
[(:blogs {:id "3x1YMtJ1CoOWk0ycYsOw4I"})]
```

In addition to `:id` the other supported query root parameters are:

* `:limit` - limits the page size to its value (i.e. `:limit 10`)
* `:skip` - skips the specified number of entries (i.e. `:skip 5` skips 5 entries)
* `:order` - allows ordering of the entries (i.e. `:order "fields.name"` ordres the dataset by `name`). Reverse ordering is supported with the addition of `-` (i.e. `:order "-fields.name"`)

### Supported parameters for Images

Any image asset is immidetally wrapped in an image entity containing three fields `:width`, `:height` and `:url`. The asset can be scaled up or down by sending the intended `:width` or `:height` parameter.

In order to see it in action, suppose your `author` has an `avatar` image and you want it constrained within a `width` of 150 pixels:

```clojure
[{:authors [({:avatar [:width
                       :height
                       :url]}
             {:width 150})]}]
```

## Usage

Require `contentql.core`:

```clojure
(ns my-project
  (:require [contentql.core :as contentql]))
```

Create a connection with `contentql/create-connection`. It receives three fields `:space-id`, `:access-token` and `:mode`. Use the space id and access token found on your Contentful dashboard. `:mode` shouild be either `:live` (for production) or `:preview` for (guess what, preview mode).

```clojure
(let [config {:space-id "c3tshf2weg8y"
              :access-token "e87aea51cfd9193df88f5a1d1b842d9a43cc4f2b02366b7c0ead54fb1b0ad6d4"
              :mode :live}
      conn (contentql/create-connection config)])
```

Once your connection is created, send it to `contentql/query` with your query along:

```clojure
(let [config {:space-id "c3tshf2weg8y"
              :access-token "e87aea51cfd9193df88f5a1d1b842d9a43cc4f2b02366b7c0ead54fb1b0ad6d4"
              :mode :live}
      conn (contentql/create-connection config)]
  (contentql/query conn '[{:blogs [:id :title]}]))
```

## Pagination

Pagination is supported by default on all query root (all your content types). Your response
will be wrapped in a map containing a `:nodes` field and an `:info` field. The former will
encapsulate the entries for the current page while info gives you some metadata about the
total universe of entries and the page you are in. This is a typical `:info` map:

```clojure
{:nodes {:total 33}
 :page {:size 4
        :current 1
        :total 9
        :has-next? true
        :has-prev? false}
 :pagination {:cursor 0
              :next-skip 4
              :prev-skip 0}}
```

This tells us that there are `33` total nodes in the dataset while the page size is `4`.
This payload represents page `1` of a total of `9` pages. There are a next page from where we
are but no previous page. The starting cursor for the page is entry `0` (the very first one)
and to get to the next page we need to skip `4` entries.

Pagination is achieved by manipulating the parameters `:limit` (to specify page size) and
`:skip` (to specify how many entries to skip). These two parameters can be sent to any query
root.

## Bugs

If you find a bug, submit a [Github issue](https://github.com/luchiniatwork/contentql/issues).

## Help

This project is looking for team members who can help this project succeed!
If you are interested in becoming a team member please open an issue.

## License

Copyright © 2017 Tiago Luchini

Distributed under the MIT License.
