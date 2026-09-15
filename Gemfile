# Dependencies for the GitHub Pages documentation site (see _config.yml).
# Only used by .github/workflows/pages.yml; irrelevant to the Gradle build.
source "https://rubygems.org"

gem "jekyll", "~> 4.3"
gem "jekyll-remote-theme", "~> 0.4"

group :jekyll_plugins do
  # Required by the just-the-docs theme itself.
  gem "jekyll-seo-tag", "~> 2.8"
  gem "jekyll-include-cache", "~> 0.2"

  # Renders Markdown that carries no YAML front matter -- which is every file
  # in this repository, deliberately, so that github.com renders them cleanly.
  gem "jekyll-optional-front-matter", "~> 0.3"
  # Rewrites [text](other.md) into working site URLs.
  gem "jekyll-relative-links", "~> 0.6"
  gem "jekyll-titles-from-headings", "~> 0.5"
  gem "jekyll-sitemap", "~> 1.4"
end

# Not shipped with Ruby 3.4+; Jekyll still needs them.
gem "csv"
gem "base64"
gem "bigdecimal"
gem "logger"
