import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "ExHentai"
    versionCode = 47
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"

    source {
        lang = "all"
        baseUrl = "https://e-hentai.org"
    }

    deeplink {
        host("e-hentai.org")
        host("exhentai.org")
        path("/g/..*/..*")
    }
}
