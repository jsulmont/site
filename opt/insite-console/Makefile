.PHONY: build install token

build:
ifndef GRAPHQL_API_URL
	$(error GRAPHQL_API_URL is undefined)
endif
	yarn
	yarn build

token:
	site -s check-token || site get-token -u admin -p admin

install:	token
	site put-static-site -d build -p _site/insite --spa true
